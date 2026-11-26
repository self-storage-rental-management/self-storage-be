package com.storagehub.service;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class ProcessingLeaseService {
    public enum Target { RESERVATION, ASSIGNMENT, CHECK_IN, RENTAL, RETURN }
    public record View(UUID reservationId, UUID ownerId, String ownerName, Instant expiresAt,
                       long revision, String sourceStamp, boolean owned, boolean occupied) {}
    private record Resource(Reservation reservation, BaseEntity entity) {}
    @FunctionalInterface public interface Command { Object run() throws Throwable; }
    private static final long LEASE_SECONDS = 120;
    private final ThreadLocal<java.util.Set<UUID>> executing = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ProcessingLeaseRepository leases;
    private final ReservationRepository reservations;
    private final CheckInRepository checkIns;
    private final RentalRepository rentals;
    private final ReturnCaseRepository returns;
    private final UserRepository users;
    private final FacilityScopeService scope;
    private final EntityManager entities;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    @Transactional(readOnly = true)
    public View status(ActorPrincipal actor, Target type, UUID id, UUID token) {
        Resource resource = resource(type, id);
        authorize(actor, type, resource.reservation(), false);
        return view(actor, token, resource, leases.findById(resource.reservation().getId()).orElse(null));
    }

    @Transactional
    public View acquire(ActorPrincipal actor, Target type, UUID id, UUID token) {
        Resource resource = lockedResource(actor, type, id);
        ProcessingLease lease = lease(resource);
        // Another user, or this user in the very same session without the matching token, keeps
        // the lease. A newer session of the same user takes it over so a re-login cannot lock the
        // operator out of their own record.
        if (active(lease) && !owns(actor, token, lease) && !sameUserNewerSession(actor, lease)) {
            return view(actor, token, resource, lease);
        }
        lease.setOwnerId(actor.userId());
        lease.setSessionId(actor.sessionId());
        lease.setToken(token);
        lease.setExpiresAt(Instant.now().plusSeconds(LEASE_SECONDS));
        leases.saveAndFlush(lease);
        return view(actor, token, resource, lease);
    }

    @Transactional
    public View heartbeat(ActorPrincipal actor, Target type, UUID id, UUID token) {
        Resource resource = lockedResource(actor, type, id);
        ProcessingLease lease = lease(resource);
        requireOwner(actor, token, lease);
        lease.setExpiresAt(Instant.now().plusSeconds(LEASE_SECONDS));
        leases.saveAndFlush(lease);
        return view(actor, token, resource, lease);
    }

    @Transactional
    public View release(ActorPrincipal actor, Target type, UUID id, UUID token) {
        Resource resource = lockedResource(actor, type, id);
        ProcessingLease lease = lease(resource);
        // Delayed cleanup from an old tab must never release another tab's lease.
        if (owns(actor, token, lease)) {
            lease.setOwnerId(null);
            lease.setSessionId(null);
            lease.setToken(null);
            lease.setExpiresAt(null);
            leases.saveAndFlush(lease);
        }
        return view(actor, token, resource, lease);
    }

    public Object command(ActorPrincipal actor, Target type, UUID id, Command command) throws Throwable {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            return command.run(); // Trusted internal calls retain their existing transaction boundaries.
        return new TransactionTemplate(transactions).execute(tx -> {
            Resource resource = resource(type, id);
            UUID reservationId = resource.reservation().getId();
            if (executing.get().contains(reservationId)) {
                try { return command.run(); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IllegalStateException("Không thể hoàn tất thao tác hồ sơ.", failure); }
            }
            // Existing customer workflows keep their own authorization; changes invalidate the source stamp.
            boolean customer = actor.hasRole(RoleCode.CUSTOMER)
                && !actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN);
            if (!customer) authorize(actor, type, resource.reservation(), true);
            lock(resource);
            ProcessingLease lease = lease(resource);
            if (!customer) {
                UUID token = parseToken(attributes.getRequest().getHeader("X-Processing-Token"));
                requireOwner(actor, token, lease);
                String expected = attributes.getRequest().getHeader("X-Processing-Version");
                String expectedStamp = attributes.getRequest().getHeader("X-Processing-Stamp");
                if (!Long.toString(lease.getRevision()).equals(expected) || !stamp(resource).equals(expectedStamp))
                    throw conflict("Hồ sơ đã thay đổi. Hãy tải lại dữ liệu trước khi tiếp tục.");
            }
            executing.get().add(reservationId);
            try {
                Object result = command.run();
                entities.flush();
                lease.setRevision(lease.getRevision() + 1);
                if (!customer) lease.setExpiresAt(Instant.now().plusSeconds(LEASE_SECONDS));
                leases.saveAndFlush(lease);
                if (attributes.getResponse() != null) {
                    attributes.getResponse().setHeader("X-Processing-Version", Long.toString(lease.getRevision()));
                    // Read the committed values back. The instances captured before the command ran
                    // still carry the pre-command updated-at, so handing that stamp to the client
                    // made its very next request look stale and blocked the whole workflow.
                    attributes.getResponse().setHeader("X-Processing-Stamp", committedStamp(type, id));
                    if (!customer) attributes.getResponse().setHeader("X-Processing-Expires-At", lease.getExpiresAt().toString());
                }
                return result;
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Không thể hoàn tất thao tác hồ sơ.", failure); }
            finally {
                executing.get().remove(reservationId);
                if (executing.get().isEmpty()) executing.remove();
            }
        });
    }

    private Resource lockedResource(ActorPrincipal actor, Target type, UUID id) {
        Resource resource = resource(type, id);
        authorize(actor, type, resource.reservation(), true);
        lock(resource);
        return resource;
    }

    private void lock(Resource resource) {
        // The canonical reservation lock serializes creation, renewal and every guarded write.
        entities.refresh(resource.reservation(), LockModeType.PESSIMISTIC_WRITE);
        if (resource.entity() != resource.reservation()) entities.refresh(resource.entity(), LockModeType.PESSIMISTIC_WRITE);
    }

    private Resource resource(Target type, UUID id) {
        return switch (type) {
            case RESERVATION, ASSIGNMENT -> {
                Reservation r = reservations.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy đơn đặt kho."));
                yield new Resource(r, r);
            }
            case CHECK_IN -> {
                CheckIn c = checkIns.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ nhận kho."));
                yield new Resource(c.getReservation(), c);
            }
            case RENTAL -> {
                Rental r = rentals.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
                yield new Resource(r.getReservation(), r);
            }
            case RETURN -> {
                ReturnCase r = returns.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ trả kho."));
                yield new Resource(r.getRental().getReservation(), r);
            }
        };
    }

    private void authorize(ActorPrincipal actor, Target type, Reservation reservation, boolean write) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Chỉ nhân sự vận hành được giữ quyền xử lý hồ sơ.");
        if (write) scope.assertCanOperate(actor, reservation.getFacility().getId());
        else scope.assertCanRead(actor, reservation.getFacility().getId());
        boolean permitted = switch (type) {
            case RESERVATION -> actor.hasPermission(SystemPermission.PERFORM_CHECKIN) || actor.hasPermission(SystemPermission.ASSIGN_UNITS);
            case ASSIGNMENT -> actor.hasPermission(SystemPermission.ASSIGN_UNITS);
            case CHECK_IN -> actor.hasPermission(SystemPermission.PERFORM_CHECKIN);
            case RENTAL -> actor.hasPermission(SystemPermission.MANAGE_RENTALS) || actor.hasPermission(SystemPermission.PERFORM_CHECKIN);
            case RETURN -> actor.hasPermission(SystemPermission.PROCESS_RETURNS)
                || actor.hasPermission(SystemPermission.MANAGE_RENTALS) || actor.hasPermission(SystemPermission.APPROVE_REFUNDS);
        };
        if (write && !permitted) throw ApiExceptions.forbidden("Bạn chưa có quyền xử lý loại hồ sơ này.");
    }

    private ProcessingLease lease(Resource resource) {
        return leases.findForUpdate(resource.reservation().getId()).orElseGet(() -> {
            ProcessingLease created = new ProcessingLease();
            created.setReservationId(resource.reservation().getId());
            return created;
        });
    }

    private boolean active(ProcessingLease lease) {
        return lease != null && lease.getToken() != null && lease.getExpiresAt() != null && lease.getExpiresAt().isAfter(Instant.now());
    }
    private boolean owns(ActorPrincipal actor, UUID token, ProcessingLease lease) {
        return lease != null && token != null && token.equals(lease.getToken())
            && actor.userId().equals(lease.getOwnerId()) && Objects.equals(actor.sessionId(), lease.getSessionId());
    }

    /**
     * True when the same user holds the lease from a different sign-in session.
     *
     * The lease records the session that took it, so after signing out and in again (a new
     * session, e.g. another tab or a re-login) the holder no longer matches and the operator was
     * locked out of a record nobody else had claimed: the panel said "Hồ sơ đang được <their own
     * name> xử lý". Ownership is about which person is working, not which browser session, so a
     * fresh session of the same user takes the lease over. A different user is still refused.
     */
    private boolean sameUserNewerSession(ActorPrincipal actor, ProcessingLease lease) {
        if (lease == null || !active(lease)) return false;
        return actor != null && actor.userId() != null && actor.userId().equals(lease.getOwnerId())
            && !Objects.equals(actor.sessionId(), lease.getSessionId());
    }
    private void requireOwner(ActorPrincipal actor, UUID token, ProcessingLease lease) {
        if (!active(lease) || !owns(actor, token, lease)) {
            // Naming the caller as the blocker reads as a bug, so tell them to re-claim instead.
            boolean heldByCaller = lease != null && active(lease) && actor != null
                && actor.userId() != null && actor.userId().equals(lease.getOwnerId());
            String name = lease != null && active(lease) && !heldByCaller
                ? users.findById(lease.getOwnerId()).map(User::getFullName).orElse("nhân sự khác") : null;
            throw conflict(name == null ? "Quyền xử lý đã hết hạn. Hãy tải lại và nhận xử lý hồ sơ."
                : "Hồ sơ đang được " + name + " xử lý. Bạn chỉ có thể xem.");
        }
    }
    private View view(ActorPrincipal actor, UUID token, Resource resource, ProcessingLease lease) {
        boolean occupied = active(lease);
        String name = occupied ? users.findById(lease.getOwnerId()).map(User::getFullName).orElse("Nhân sự vận hành") : null;
        return new View(resource.reservation().getId(), occupied ? lease.getOwnerId() : null, name,
            occupied ? lease.getExpiresAt() : null, lease == null ? 0 : lease.getRevision(), stamp(resource),
            occupied && owns(actor, token, lease), occupied);
    }
    private String stamp(Resource resource) {
        return time(resource.reservation().getUpdatedAt()) + ":" + time(resource.entity().getUpdatedAt());
    }

    /**
     * Stamp built from freshly read rows, for use right after a command committed.
     *
     * The {@code Resource} passed into {@link #command} was loaded before the command ran, so its
     * updated-at values are the in-memory ones the command produced. MySQL stores timestamp(6) by
     * rounding to the microsecond while the captured Instant is truncated, so the in-memory value
     * can differ from the stored one by one microsecond. Handing that stamp to the client made the
     * record look changed on its very next request, and the workflow locked up.
     *
     * Only the two rows behind this record are detached; the rest of the persistence context is
     * left intact so the caller can still serialize its result.
     */
    private String committedStamp(Target type, UUID id) {
        Resource current = resource(type, id);
        entities.detach(current.entity());
        if (current.reservation() != current.entity()) entities.detach(current.reservation());
        return stamp(resource(type, id));
    }

    private String time(Instant value) { return value == null ? "0" : value.truncatedTo(ChronoUnit.MICROS).toString(); }
    private UUID parseToken(String token) {
        try { return UUID.fromString(token); }
        catch (Exception invalid) { throw conflict("Cần nhận quyền xử lý hồ sơ trước khi ghi nhận."); }
    }
    private ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message, Map.of("processingConflict", true));
    }
}
