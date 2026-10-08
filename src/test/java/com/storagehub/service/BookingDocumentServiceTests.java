package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.common.api.ApiException;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.BookingDocument;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.BookingDocumentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingDocumentServiceTests {

    @Mock private BookingDocumentRepository documentRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private FileAssetRepository fileAssetRepository;
    @Mock private AuditLogService auditLogService;

    @TempDir Path temporaryDirectory;

    private BookingDocumentService service;
    private ActorPrincipal actor;
    private Reservation reservation;
    private ReservationPricingSnapshot snapshot;

    @BeforeEach
    void setUp() {
        FileProperties properties = new FileProperties();
        properties.setStoragePath(temporaryDirectory.toString());
        service = new BookingDocumentService(
            documentRepository, reservationRepository, snapshotRepository, fileAssetRepository,
            properties, auditLogService
        );

        User customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setFullName("Nguyen Van A");
        actor = new ActorPrincipal(customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());

        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setName("StorageHub District 1");
        facility.setAddress("125 Nguyen Binh Khiem, Phuong Ben Nghe, Quan 1, TP. Ho Chi Minh");
        UnitType unitType = new UnitType();
        unitType.setName("Medium");

        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setReservationCode("RSV-TEST001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setStartDate(LocalDate.of(2026, 12, 1));
        reservation.setEndDate(LocalDate.of(2027, 1, 1));

        snapshot = new ReservationPricingSnapshot();
        snapshot.setReservation(reservation);
        snapshot.setPricingPackageCode("MONTHLY");
        snapshot.setRentalMonths(1);
        snapshot.setMonthlyPrice(new BigDecimal("1000000.00"));
        snapshot.setGrossRentalAmount(new BigDecimal("1000000.00"));
        snapshot.setDiscountRate(BigDecimal.ZERO);
        snapshot.setDiscountAmount(BigDecimal.ZERO);
        snapshot.setNetRentalAmount(new BigDecimal("1000000.00"));
        snapshot.setReservationDepositAmount(new BigDecimal("400000.00"));
        snapshot.setSecurityDepositAmount(new BigDecimal("1000000.00"));
        snapshot.setRemainingRentalAmount(new BigDecimal("600000.00"));
        snapshot.setDueAtCheckIn(new BigDecimal("1600000.00"));
        snapshot.setTotalInitialObligation(new BigDecimal("2000000.00"));
    }

    @Test
    void generatesPdfFromConfirmedReservationAndPersistsChecksum() throws Exception {
        AtomicReference<Path> storedPath = new AtomicReference<>();
        when(documentRepository.findByReservation_IdAndReservation_Customer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(fileAssetRepository.saveAndFlush(any(FileAsset.class))).thenAnswer(invocation -> {
            FileAsset asset = invocation.getArgument(0);
            storedPath.set(Path.of(asset.getStorageKey()));
            ReflectionTestUtils.setField(asset, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(asset, "createdAt", Instant.now());
            return asset;
        });
        when(documentRepository.saveAndFlush(any(BookingDocument.class))).thenAnswer(invocation -> {
            BookingDocument document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
            return document;
        });

        var response = service.generate(actor, reservation.getId());

        assertThat(response.documentType().name()).isEqualTo("BOOKING_CONFIRMATION");
        assertThat(response.fileName()).startsWith("phieu-xac-nhan-giu-kho-");
        assertThat(response.checksumSha256()).hasSize(64);
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(Files.readAllBytes(storedPath.get())).startsWith("%PDF-1.4".getBytes());
        assertThat(Files.size(storedPath.get())).isGreaterThan(50_000L);
        String previewPath = System.getProperty("booking.document.preview");
        if (previewPath != null && !previewPath.isBlank()) {
            Path preview = Path.of(previewPath).toAbsolutePath();
            Files.createDirectories(preview.getParent());
            Files.copy(storedPath.get(), preview, StandardCopyOption.REPLACE_EXISTING);
        }
        verify(documentRepository).saveAndFlush(any(BookingDocument.class));
    }

    @Test
    void generatesPdfWithHistoricalDiscountSnapshotEvenWhenPolicyIsChanged() throws Exception {
        snapshot.setRentalMonths(3);
        snapshot.setGrossRentalAmount(new BigDecimal("7500000.00"));
        snapshot.setPricingPackageCode("PKG-3M");
        snapshot.setDiscountRate(new BigDecimal("0.0500"));
        snapshot.setDiscountAmount(new BigDecimal("375000.00"));
        snapshot.setNetRentalAmount(new BigDecimal("7125000.00"));

        AtomicReference<Path> storedPath = new AtomicReference<>();
        when(documentRepository.findByReservation_IdAndReservation_Customer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(fileAssetRepository.saveAndFlush(any(FileAsset.class))).thenAnswer(invocation -> {
            FileAsset asset = invocation.getArgument(0);
            storedPath.set(Path.of(asset.getStorageKey()));
            ReflectionTestUtils.setField(asset, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(asset, "createdAt", Instant.now());
            return asset;
        });
        when(documentRepository.saveAndFlush(any(BookingDocument.class))).thenAnswer(invocation -> {
            BookingDocument document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
            return document;
        });

        var response = service.generate(actor, reservation.getId());

        assertThat(response).isNotNull();
        assertThat(response.fileName()).startsWith("phieu-xac-nhan-giu-kho-");
        assertThat(Files.size(storedPath.get())).isGreaterThan(50_000L);
    }

    @Test
    void returnsExistingDocumentWithoutGeneratingAnotherFile() {
        FileAsset asset = new FileAsset();
        ReflectionTestUtils.setField(asset, "id", UUID.randomUUID());
        asset.setOriginalName("phieu-xac-nhan-giu-kho-rsv-test001.pdf");
        asset.setContentType("application/pdf");
        asset.setSizeBytes(20);
        asset.setChecksumSha256("a".repeat(64));
        BookingDocument document = new BookingDocument();
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
        document.setReservation(reservation);
        document.setFileAsset(asset);
        document.setIssuedAt(Instant.now());
        when(documentRepository.findByReservation_IdAndReservation_Customer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(document));

        var response = service.generate(actor, reservation.getId());

        assertThat(response.id()).isEqualTo(document.getId());
        verify(reservationRepository, never()).findOwnedByIdForUpdate(any(), any());
    }

    @Test
    void replacesLegacyEnglishDocumentWithCurrentVietnameseTemplate() throws Exception {
        UUID fileId = UUID.randomUUID();
        Path legacyPath = temporaryDirectory.resolve(fileId + ".pdf");
        Files.writeString(legacyPath, "legacy booking confirmation");

        FileAsset asset = new FileAsset();
        ReflectionTestUtils.setField(asset, "id", fileId);
        asset.setUploadedBy(reservation.getCustomer());
        asset.setStorageKey(legacyPath.toString());
        asset.setOriginalName("booking-confirmation-rsv-test001.pdf");
        asset.setContentType("application/pdf");
        asset.setSizeBytes(Files.size(legacyPath));
        asset.setChecksumSha256("a".repeat(64));
        asset.setStatus(com.storagehub.domain.model.FileAssetStatus.ACTIVE);

        BookingDocument document = new BookingDocument();
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
        document.setReservation(reservation);
        document.setFileAsset(asset);
        document.setIssuedAt(Instant.now());

        when(documentRepository.findByReservation_IdAndReservation_Customer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(document));
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(fileAssetRepository.saveAndFlush(asset)).thenReturn(asset);
        when(documentRepository.saveAndFlush(document)).thenReturn(document);

        var response = service.generate(actor, reservation.getId());

        assertThat(response.id()).isEqualTo(document.getId());
        assertThat(response.fileName()).startsWith("phieu-xac-nhan-giu-kho-");
        assertThat(Files.readAllBytes(legacyPath)).startsWith("%PDF-1.4".getBytes());
        assertThat(Files.size(legacyPath)).isGreaterThan(50_000L);
        verify(documentRepository).saveAndFlush(document);
    }

    @Test
    void refusesDocumentBeforePaymentConfirmation() {
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        when(documentRepository.findByReservation_IdAndReservation_Customer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.generate(actor, reservation.getId()))
            .isInstanceOf(ApiException.class);
        verify(fileAssetRepository, never()).saveAndFlush(any());
    }
}
