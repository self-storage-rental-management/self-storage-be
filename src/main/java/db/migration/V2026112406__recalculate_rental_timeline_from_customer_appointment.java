package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Repairs timelines written by the old receipt-time implementation.
 *
 * <p>The appointment selected by the customer is the only source for the
 * handover timeline. Existing reservations are recalculated once, including
 * their rental, check-in and signed-contract dates. Actual arrival and
 * verification timestamps are deliberately left untouched.</p>
 */
public class V2026112406__recalculate_rental_timeline_from_customer_appointment
    extends BaseJavaMigration {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Override
    public Integer getChecksum() {
        return 2026112406;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean managesTransaction = connection.getAutoCommit();
        if (managesTransaction) connection.setAutoCommit(false);

        int repairedReservations = 0;
        int repairedRentals = 0;
        int repairedCheckIns = 0;
        try {
            String selectSql = """
                SELECT r.id,
                       r.appointment_at,
                       c.scheduled_at,
                       r.start_date AS reservation_start_date,
                       r.setup_days_snapshot,
                       s.free_setup_days,
                       s.rental_months,
                       rt.id AS rental_id,
                       rt.setup_ends_at AS rental_setup_ends_at,
                       rt.setup_completion_deadline_at AS rental_setup_completion_deadline_at
                  FROM reservations r
                  JOIN reservation_pricing_snapshots s ON s.reservation_id = r.id
             LEFT JOIN check_ins c ON c.reservation_id = r.id
             LEFT JOIN rentals rt ON rt.reservation_id = r.id
                 WHERE s.rental_months > 0
                   AND (r.appointment_at IS NOT NULL
                        OR c.scheduled_at IS NOT NULL
                        OR r.start_date IS NOT NULL)
                """;
            String updateReservationSql = """
                UPDATE reservations
                   SET appointment_at = ?, start_date = ?, end_date = ?
                 WHERE id = ?
                """;
            String updateRentalSql = """
                UPDATE rentals
                   SET start_date = ?, contract_end_date = ?,
                       setup_started_at = ?, setup_ends_at = ?, terms_effective_at = ?,
                       setup_completion_deadline_at = CASE
                           WHEN setup_completion_deadline_at IS NOT NULL
                                AND setup_ends_at IS NOT NULL THEN ?
                           ELSE setup_completion_deadline_at
                       END
                 WHERE id = ?
                """;
            String updateCheckInSql = """
                UPDATE check_ins
                   SET scheduled_at = ?,
                       setup_started_at = CASE
                           WHEN setup_started_at IS NOT NULL OR setup_due_at IS NOT NULL THEN ?
                           ELSE setup_started_at
                       END,
                       setup_due_at = CASE
                           WHEN setup_started_at IS NOT NULL OR setup_due_at IS NOT NULL THEN ?
                           ELSE setup_due_at
                       END
                 WHERE reservation_id = ?
                """;
            String updateContractSql = """
                UPDATE contracts c
                   JOIN rentals r ON r.id = c.rental_id
                   SET c.expires_at = ?
                 WHERE r.id = ? AND c.status = 'SIGNED'
                """;

            try (PreparedStatement select = connection.prepareStatement(selectSql);
                 PreparedStatement updateReservation = connection.prepareStatement(updateReservationSql);
                 PreparedStatement updateRental = connection.prepareStatement(updateRentalSql);
                 PreparedStatement updateCheckIn = connection.prepareStatement(updateCheckInSql);
                 PreparedStatement updateContract = connection.prepareStatement(updateContractSql);
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    byte[] reservationId = rows.getBytes("id");
                    Instant appointmentAt = instant(rows.getTimestamp("appointment_at"));
                    if (appointmentAt == null) appointmentAt = instant(rows.getTimestamp("scheduled_at"));

                    int reservationSetupDays = rows.getInt("setup_days_snapshot");
                    int snapshotSetupDays = rows.getInt("free_setup_days");
                    int freeSetupDays = reservationSetupDays > 0 ? reservationSetupDays : snapshotSetupDays;
                    int rentalMonths = rows.getInt("rental_months");
                    if (appointmentAt == null) {
                        java.sql.Date storedStart = rows.getDate("reservation_start_date");
                        if (storedStart == null) continue;
                        appointmentAt = storedStart.toLocalDate()
                            .minusDays(freeSetupDays)
                            .atStartOfDay(BUSINESS_ZONE)
                            .toInstant();
                    }
                    Timeline timeline = timeline(appointmentAt, freeSetupDays, rentalMonths);

                    updateReservation.setTimestamp(1, Timestamp.from(appointmentAt));
                    updateReservation.setDate(2, java.sql.Date.valueOf(timeline.paidRentalStart()));
                    updateReservation.setDate(3, java.sql.Date.valueOf(timeline.contractEndDate()));
                    updateReservation.setBytes(4, reservationId);
                    repairedReservations += updateReservation.executeUpdate();

                    updateCheckIn.setTimestamp(1, Timestamp.from(appointmentAt));
                    updateCheckIn.setTimestamp(2, Timestamp.from(appointmentAt));
                    updateCheckIn.setTimestamp(3, Timestamp.from(timeline.freeSetupEndsAt()));
                    updateCheckIn.setBytes(4, reservationId);
                    if (updateCheckIn.executeUpdate() > 0) repairedCheckIns++;

                    byte[] rentalId = rows.getBytes("rental_id");
                    if (rentalId == null) continue;

                    updateRental.setDate(1, java.sql.Date.valueOf(timeline.paidRentalStart()));
                    updateRental.setDate(2, java.sql.Date.valueOf(timeline.contractEndDate()));
                    updateRental.setTimestamp(3, Timestamp.from(appointmentAt));
                    updateRental.setTimestamp(4, Timestamp.from(timeline.freeSetupEndsAt()));
                    updateRental.setTimestamp(5, Timestamp.from(appointmentAt));
                    Instant previousSetupEndsAt = instant(rows.getTimestamp("rental_setup_ends_at"));
                    Instant previousSetupDeadline = instant(rows.getTimestamp("rental_setup_completion_deadline_at"));
                    Instant shiftedSetupDeadline = previousSetupDeadline;
                    if (previousSetupEndsAt != null && previousSetupDeadline != null) {
                        shiftedSetupDeadline = previousSetupDeadline.plus(
                            Duration.between(previousSetupEndsAt, timeline.freeSetupEndsAt())
                        );
                    }
                    updateRental.setTimestamp(6,
                        shiftedSetupDeadline == null ? null : Timestamp.from(shiftedSetupDeadline));
                    updateRental.setBytes(7, rentalId);
                    repairedRentals += updateRental.executeUpdate();

                    updateContract.setTimestamp(1, Timestamp.from(
                        timeline.contractEndDate().atStartOfDay(BUSINESS_ZONE).toInstant()));
                    updateContract.setBytes(2, rentalId);
                    updateContract.executeUpdate();
                }
            }
            if (managesTransaction) connection.commit();
            System.out.println("Rental timeline repair completed: reservations=" + repairedReservations
                + ", rentals=" + repairedRentals + ", checkIns=" + repairedCheckIns);
        } catch (Exception failure) {
            if (managesTransaction) connection.rollback();
            throw failure;
        } finally {
            if (managesTransaction) connection.setAutoCommit(true);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timeline timeline(Instant appointmentAt, int freeSetupDays, int rentalMonths) {
        if (freeSetupDays < 0) throw new IllegalStateException("Negative free setup days in pricing snapshot");
        if (rentalMonths < 1) throw new IllegalStateException("Invalid rental months in pricing snapshot");
        return new Timeline(
            appointmentAt.plus(Duration.ofDays(freeSetupDays)),
            appointmentAt.atZone(BUSINESS_ZONE).toLocalDate().plusDays(freeSetupDays),
            appointmentAt.atZone(BUSINESS_ZONE).toLocalDate().plusDays(freeSetupDays).plusMonths(rentalMonths)
        );
    }

    private record Timeline(Instant freeSetupEndsAt, LocalDate paidRentalStart, LocalDate contractEndDate) {}
}
