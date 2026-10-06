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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingDocumentService {

    private static final String CURRENT_FILE_PREFIX = "phieu-xac-nhan-giu-kho-";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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
        if (existing != null && isCurrentTemplate(existing.getFileAsset())) {
            return toResponse(existing);
        }

        Reservation reservation = existing != null ? existing.getReservation() : reservationRepository
            .findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        requireConfirmed(reservation);
        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));

        byte[] pdf = buildPdf(reservation, snapshot);
        UUID fileId = existing == null ? UUID.randomUUID() : existing.getFileAsset().getId();
        String fileName = CURRENT_FILE_PREFIX + reservation.getReservationCode().toLowerCase(Locale.ROOT) + ".pdf";
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
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            FileAsset asset = existing == null ? new FileAsset() : existing.getFileAsset();
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

            BookingDocument document = existing == null ? new BookingDocument() : existing;
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
            .orElseThrow(() -> ApiExceptions.notFound("Hold confirmation was not found"));
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public DownloadedDocument download(ActorPrincipal actor, UUID reservationId) {
        BookingDocument document = documentRepository
            .findByReservation_IdAndReservation_Customer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Booking document was not found"));
        FileAsset asset = document.getFileAsset();
        if (asset.getStatus() != FileAssetStatus.ACTIVE) {
            throw ApiExceptions.notFound("Hold confirmation file was not found");
        }
        try {
            Path path = Path.of(asset.getStorageKey()).toAbsolutePath().normalize();
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw ApiExceptions.notFound("Hold confirmation file was not found");
            }
            return new DownloadedDocument(resource, asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes());
        } catch (java.net.MalformedURLException exception) {
            throw ApiExceptions.notFound("Hold confirmation file was not found");
        }
    }

    private void requireConfirmed(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
            && reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN
            && reservation.getStatus() != ReservationStatus.AWAITING_CUSTOMER_RECEIPT
            && reservation.getStatus() != ReservationStatus.COMPLETED) {
            throw ApiExceptions.conflict("Hold confirmation is available only after the reservation deposit is paid");
        }
    }

    private boolean isCurrentTemplate(FileAsset asset) {
        return asset != null
            && asset.getOriginalName() != null
            && asset.getOriginalName().startsWith(CURRENT_FILE_PREFIX);
    }

    private byte[] buildPdf(Reservation reservation, ReservationPricingSnapshot snapshot) {
        int width = 1240;
        int height = 1754;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(248, 247, 242));
        graphics.fillRect(0, 0, width, height);

        Font regular = new Font(Font.SANS_SERIF, Font.PLAIN, 25);
        Font medium = regular.deriveFont(Font.BOLD, 25f);
        Font small = regular.deriveFont(Font.PLAIN, 21f);
        Font title = regular.deriveFont(Font.BOLD, 44f);
        Font section = regular.deriveFont(Font.BOLD, 28f);
        Color ink = new Color(37, 36, 32);
        Color muted = new Color(111, 105, 96);
        Color orange = new Color(237, 158, 31);
        Color green = new Color(4, 137, 98);

        graphics.setColor(new Color(38, 39, 35));
        graphics.fillRect(0, 0, width, 185);
        graphics.setFont(title.deriveFont(36f));
        graphics.setColor(Color.WHITE);
        graphics.drawString("Storage", 82, 83);
        int storageWidth = graphics.getFontMetrics().stringWidth("Storage");
        graphics.setColor(orange);
        graphics.drawString("Hub", 82 + storageWidth, 83);
        graphics.setFont(small);
        graphics.setColor(new Color(222, 217, 205));
        graphics.drawString("LƯU TRỮ AN TOÀN", 82, 125);

        graphics.setColor(ink);
        graphics.setFont(title);
        graphics.drawString("PHIẾU XÁC NHẬN GIỮ KHO", 82, 265);
        graphics.setFont(regular);
        graphics.setColor(muted);
        graphics.drawString("Ghi nhận loại kho đã giữ và khoản cọc giữ chỗ đã thanh toán", 82, 310);
        drawPill(graphics, 892, 224, 266, 58, new Color(224, 247, 239), green, "ĐÃ THANH TOÁN CỌC", medium.deriveFont(19f));

        drawCard(graphics, 72, 355, 1096, 500, Color.WHITE, new Color(225, 220, 210));
        graphics.setFont(section);
        graphics.setColor(ink);
        graphics.drawString("Thông tin giữ kho", 105, 410);
        graphics.setColor(orange);
        graphics.fillRoundRect(105, 430, 80, 5, 3, 3);

        drawLabelValue(graphics, "Mã đơn giữ kho", reservation.getReservationCode(), 105, 490, regular, medium, muted, ink);
        drawLabelValue(graphics, "Khách hàng", reservation.getCustomer().getFullName(), 640, 490, regular, medium, muted, ink);
        drawLabelValue(graphics, "Cơ sở", reservation.getFacility().getName(), 105, 585, regular, medium, muted, ink);
        drawLabelValue(graphics, "Loại kho giữ chỗ", reservation.getUnitType().getName(), 640, 585, regular, medium, muted, ink);
        String rentalPeriod = date(reservation.getStartDate()) + " - " + date(reservation.getEndDate());
        drawLabelValue(graphics, "Kỳ thuê dự kiến", rentalPeriod, 105, 680, regular, medium, muted, ink);
        String rentalMonths = snapshot.getRentalMonths() > 0 ? snapshot.getRentalMonths() + " tháng" : "-";
        drawLabelValue(graphics, "Thời hạn", rentalMonths, 640, 680, regular, medium, muted, ink);
        drawLabelValue(graphics, "Địa chỉ cơ sở", nullToDash(reservation.getFacility().getAddress()), 105, 775, regular, medium.deriveFont(22f), muted, ink);

        drawCard(graphics, 72, 890, 1096, 570, Color.WHITE, new Color(225, 220, 210));
        graphics.setFont(section);
        graphics.setColor(ink);
        graphics.drawString("Chi tiết chi phí", 105, 945);
        graphics.setColor(orange);
        graphics.fillRoundRect(105, 965, 80, 5, 3, 3);

        int y = 1020;
        y = drawMoneyRow(graphics, "Đơn giá mỗi tháng", snapshot.getMonthlyPrice(), y, regular, medium, ink, muted, false);
        y = drawMoneyRow(graphics, "Tiền thuê trước giảm", snapshot.getGrossRentalAmount(), y, regular, medium, ink, muted, false);
        if (positive(snapshot.getDiscountAmount())) {
            String rate = snapshot.getDiscountRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
            y = drawMoneyRow(graphics, "Ưu đãi (" + rate + "%)", snapshot.getDiscountAmount().negate(), y, regular, medium, green, muted, false);
        }
        y = drawMoneyRow(graphics, "Tiền thuê sau giảm", snapshot.getNetRentalAmount(), y, regular, medium, ink, muted, true);
        y = drawMoneyRow(graphics, "Cọc giữ chỗ đã thanh toán", snapshot.getReservationDepositAmount(), y, regular, medium, orange, muted, true);
        y = drawMoneyRow(graphics, "Tiền thuê còn lại", snapshot.getRemainingRentalAmount(), y, regular, medium, ink, muted, false);
        y = drawMoneyRow(graphics, "Tiền đảm bảo kho", snapshot.getSecurityDepositAmount(), y, regular, medium, ink, muted, false);
        drawMoneyRow(graphics, "Tổng thanh toán khi nhận kho", snapshot.getDueAtCheckIn(), y, regular, medium, ink, muted, true);

        drawCard(graphics, 72, 1490, 1096, 140, new Color(255, 248, 230), new Color(246, 199, 92));
        graphics.setFont(medium);
        graphics.setColor(new Color(125, 78, 5));
        graphics.drawString("Lưu ý", 105, 1535);
        graphics.setFont(small);
        graphics.setColor(new Color(91, 70, 35));
        graphics.drawString("Phiếu này xác nhận giữ loại kho và khoản cọc đã ghi nhận.", 105, 1573);
        graphics.drawString("Phiếu không phải hợp đồng thuê và không xác nhận một gian kho vật lý cụ thể.", 105, 1605);

        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(new Color(211, 206, 196));
        graphics.drawLine(82, 1670, 1158, 1670);
        graphics.setFont(small.deriveFont(19f));
        graphics.setColor(muted);
        graphics.drawString("Phát hành lúc " + issuedAt(), 82, 1710);
        drawRight(graphics, "StorageHub - " + reservation.getReservationCode(), 1158, 1710);
        graphics.dispose();
        return imagePdf(image);
    }

    private void drawCard(Graphics2D graphics, int x, int y, int width, int height, Color fill, Color border) {
        graphics.setColor(fill);
        graphics.fillRoundRect(x, y, width, height, 26, 26);
        graphics.setColor(border);
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRoundRect(x, y, width, height, 26, 26);
    }

    private void drawPill(Graphics2D graphics, int x, int y, int width, int height, Color fill, Color text, String value, Font font) {
        graphics.setColor(fill);
        graphics.fillRoundRect(x, y, width, height, height, height);
        graphics.setFont(font);
        graphics.setColor(text);
        int textWidth = graphics.getFontMetrics().stringWidth(value);
        graphics.drawString(value, x + (width - textWidth) / 2, y + 38);
    }

    private void drawLabelValue(Graphics2D graphics, String label, String value, int x, int y, Font regular, Font medium, Color muted, Color ink) {
        graphics.setFont(regular.deriveFont(20f));
        graphics.setColor(muted);
        graphics.drawString(label, x, y);
        graphics.setFont(medium);
        graphics.setColor(ink);
        graphics.drawString(nullToDash(value), x, y + 38);
    }

    private int drawMoneyRow(Graphics2D graphics, String label, BigDecimal value, int y, Font regular, Font medium, Color valueColor, Color muted, boolean emphasized) {
        graphics.setFont(emphasized ? medium : regular);
        graphics.setColor(emphasized ? new Color(37, 36, 32) : muted);
        graphics.drawString(label, 105, y);
        graphics.setFont(medium);
        graphics.setColor(valueColor);
        drawRight(graphics, money(value), 1135, y);
        graphics.setColor(new Color(235, 231, 223));
        graphics.drawLine(105, y + 25, 1135, y + 25);
        return y + 60;
    }

    private void drawRight(Graphics2D graphics, String value, int right, int y) {
        graphics.drawString(value, right - graphics.getFontMetrics().stringWidth(value), y);
    }

    private byte[] imagePdf(BufferedImage image) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(image.getWidth() * image.getHeight() * 3);
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(raw)) {
                byte[] rgbBytes = new byte[image.getWidth() * image.getHeight() * 3];
                int byteIndex = 0;
                int[] row = new int[image.getWidth()];
                for (int y = 0; y < image.getHeight(); y++) {
                    image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                    for (int rgb : row) {
                        rgbBytes[byteIndex++] = (byte) ((rgb >> 16) & 0xff);
                        rgbBytes[byteIndex++] = (byte) ((rgb >> 8) & 0xff);
                        rgbBytes[byteIndex++] = (byte) (rgb & 0xff);
                    }
                }
                compressed.write(rgbBytes);
            }
            byte[] imageStream = raw.toByteArray();
            byte[] pageStream = "q\n595 0 0 842 0 0 cm\n/Im0 Do\nQ\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            write(output, "%PDF-1.4\n");
            int[] offsets = new int[6];
            offsets[1] = output.size(); write(output, "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
            offsets[2] = output.size(); write(output, "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n");
            offsets[3] = output.size(); write(output, "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</XObject<</Im0 4 0 R>>>>/Contents 5 0 R>>endobj\n");
            offsets[4] = output.size(); write(output, "4 0 obj<</Type/XObject/Subtype/Image/Width " + image.getWidth() + "/Height " + image.getHeight() + "/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/FlateDecode/Length " + imageStream.length + ">>stream\n");
            output.write(imageStream); write(output, "\nendstream\nendobj\n");
            offsets[5] = output.size(); write(output, "5 0 obj<</Length " + pageStream.length + ">>stream\n");
            output.write(pageStream); write(output, "endstream\nendobj\n");
            int xref = output.size();
            write(output, "xref\n0 6\n0000000000 65535 f \n");
            for (int index = 1; index <= 5; index++) write(output, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[index]));
            write(output, "trailer<</Size 6/Root 1 0 R>>\nstartxref\n" + xref + "\n%%EOF\n");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not render hold confirmation", exception);
        }
    }

    private void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String date(java.time.LocalDate value) {
        return value == null ? "-" : value.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private String issuedAt() {
        return DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(VIETNAM_ZONE).format(Instant.now());
    }

    private String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN")).format(amount);
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
