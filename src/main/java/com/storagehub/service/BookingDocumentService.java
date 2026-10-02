package com.storagehub.service;

import com.storagehub.api.reservation.BookingDocumentResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.BookingDocument;
import com.storagehub.domain.model.BookingDocumentType;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.repo.BookingDocumentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingDocumentService {

    private final BookingDocumentRepository documentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final FileAssetRepository fileAssetRepository;
    private final FileProperties fileProperties;
    private final AuditLogService auditLogService;

    @Transactional
    public BookingDocumentResponse generate(ActorPrincipal actor, UUID reservationId) {
        BookingDocument existing = documentRepository
            .findByReservation_IdAndReservation_Customer_Id(reservationId, actor.userId())
            .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        Reservation reservation = reservationRepository
            .findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        requireConfirmed(reservation);
        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));

        byte[] pdf = buildPdf(reservation, snapshot);
        UUID fileId = UUID.randomUUID();
        String fileName = "booking-confirmation-" + reservation.getReservationCode().toLowerCase(Locale.ROOT) + ".pdf";
        Path root = Path.of(fileProperties.getStoragePath()).toAbsolutePath().normalize();
        Path target = root.resolve(fileId + ".pdf").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("Invalid booking document path");
        }
        Path temporary = root.resolve(fileId + ".uploading").normalize();

        try {
            Files.createDirectories(root);
            Files.write(temporary, pdf);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            FileAsset asset = new FileAsset();
            asset.setUploadedBy(reservation.getCustomer());
            asset.setStorageKey(target.toString());
            asset.setOriginalName(fileName);
            asset.setContentType("application/pdf");
            asset.setSizeBytes(pdf.length);
            asset.setChecksumSha256(sha256(pdf));
            asset.setEntityType("BOOKING_DOCUMENT");
            asset.setEntityId(reservationId);
            asset.setStatus(FileAssetStatus.ACTIVE);
            FileAsset savedAsset = fileAssetRepository.saveAndFlush(asset);

            BookingDocument document = new BookingDocument();
            document.setReservation(reservation);
            document.setFileAsset(savedAsset);
            document.setDocumentType(BookingDocumentType.BOOKING_CONFIRMATION);
            document.setIssuedAt(Instant.now());
            BookingDocument saved = documentRepository.saveAndFlush(document);
            BookingDocumentResponse response = toResponse(saved);
            auditLogService.recordMutation(
                reservation.getCustomer(), "BOOKING_CONFIRMATION_ISSUED", "BookingDocument",
                saved.getId(), reservation.getFacility().getId(), null, response
            );
            return response;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw new IllegalStateException("Booking document generation failed", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public BookingDocumentResponse get(ActorPrincipal actor, UUID reservationId) {
        BookingDocument document = documentRepository
            .findByReservation_IdAndReservation_Customer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Booking document was not found"));
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public DownloadedDocument download(ActorPrincipal actor, UUID reservationId) {
        BookingDocument document = documentRepository
            .findByReservation_IdAndReservation_Customer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Booking document was not found"));
        FileAsset asset = document.getFileAsset();
        if (asset.getStatus() != FileAssetStatus.ACTIVE) {
            throw ApiExceptions.notFound("Booking document file was not found");
        }
        try {
            Path path = Path.of(asset.getStorageKey()).toAbsolutePath().normalize();
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw ApiExceptions.notFound("Booking document file was not found");
            }
            return new DownloadedDocument(resource, asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes());
        } catch (java.net.MalformedURLException exception) {
            throw ApiExceptions.notFound("Booking document file was not found");
        }
    }

    private void requireConfirmed(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
            && reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN
            && reservation.getStatus() != ReservationStatus.AWAITING_CUSTOMER_RECEIPT
            && reservation.getStatus() != ReservationStatus.COMPLETED) {
            throw ApiExceptions.conflict("Booking confirmation is available only after payment confirmation");
        }
    }

    private byte[] buildPdf(Reservation reservation, ReservationPricingSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add("STORAGEHUB - BOOKING CONFIRMATION");
        lines.add("This document confirms a reservation; it is not a signed rental contract.");
        lines.add("Reservation: " + reservation.getReservationCode());
        lines.add("Customer: " + ascii(reservation.getCustomer().getFullName()));
        lines.add("Facility: " + ascii(reservation.getFacility().getName()));
        lines.add("Unit type: " + ascii(reservation.getUnitType().getName()));
        lines.add("Rental period: " + reservation.getStartDate() + " to " + reservation.getEndDate());
        if (snapshot.getRentalMonths() > 0) {
            lines.add("Rental duration: " + snapshot.getRentalMonths() + " month(s)");
        }
        if (snapshot.getGrossRentalAmount() != null) {
            lines.add("Gross rental amount: " + money(snapshot.getGrossRentalAmount()) + " VND");
        }
        lines.add("Package / Discount Code: " + ascii(snapshot.getPricingPackageCode()));
        if (snapshot.getDiscountRate() != null && snapshot.getDiscountRate().compareTo(BigDecimal.ZERO) > 0) {
            lines.add("Discount rate applied: " + snapshot.getDiscountRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        }
        if (snapshot.getDiscountAmount() != null && snapshot.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            lines.add("Discount savings: -" + money(snapshot.getDiscountAmount()) + " VND");
        }
        lines.add("Net rental amount: " + money(snapshot.getNetRentalAmount()) + " VND");
        lines.add("Reservation deposit paid: " + money(snapshot.getReservationDepositAmount()) + " VND");
        lines.add("Security deposit due at check-in: " + money(snapshot.getSecurityDepositAmount()) + " VND");
        lines.add("Issued at: " + Instant.now());
        return minimalPdf(lines);
    }

    private byte[] minimalPdf(List<String> lines) {
        StringBuilder content = new StringBuilder("BT\n/F1 12 Tf\n50 790 Td\n");
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) content.append("0 -24 Td\n");
            content.append('(').append(pdfEscape(lines.get(index))).append(") Tj\n");
        }
        content.append("ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            int[] offsets = new int[6];
            offsets[1] = output.size(); write(output, "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
            offsets[2] = output.size(); write(output, "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n");
            offsets[3] = output.size(); write(output, "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n");
            offsets[4] = output.size(); write(output, "4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n");
            offsets[5] = output.size(); write(output, "5 0 obj<</Length " + stream.length + ">>stream\n");
            output.write(stream); write(output, "endstream\nendobj\n");
            int xref = output.size();
            write(output, "xref\n0 6\n0000000000 65535 f \n");
            for (int index = 1; index <= 5; index++) {
                write(output, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[index]));
            }
            write(output, "trailer<</Size 6/Root 1 0 R>>\nstartxref\n" + xref + "\n%%EOF\n");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not render booking confirmation", exception);
        }
    }

    private void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String pdfEscape(String value) {
        return ascii(value).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private String ascii(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
            .replaceAll("[^\\x20-\\x7E]", "?");
    }

    private String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2).toPlainString();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BookingDocumentResponse toResponse(BookingDocument document) {
        FileAsset asset = document.getFileAsset();
        UUID reservationId = document.getReservation().getId();
        return new BookingDocumentResponse(
            document.getId(), reservationId, document.getReservation().getReservationCode(),
            document.getDocumentType(), asset.getId(), asset.getOriginalName(), asset.getContentType(),
            asset.getSizeBytes(), asset.getChecksumSha256(), document.getIssuedAt(),
            "/api/customer/reservations/" + reservationId + "/booking-document/download"
        );
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public record DownloadedDocument(Resource resource, String fileName, String contentType, long sizeBytes) {
    }
}
