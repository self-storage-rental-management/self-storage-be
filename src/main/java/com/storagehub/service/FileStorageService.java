package com.storagehub.service;

import com.storagehub.api.file.FileAssetResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final Map<String, String> EXTENSIONS = Map.of(
        "image/jpeg", ".jpg",
        "image/png", ".png",
        "image/webp", ".webp",
        "application/pdf", ".pdf"
    );

    private final FileProperties properties;
    private final FileAssetRepository fileAssetRepository;
    private final CheckInRepository checkInRepository;
    private final ReservationGoodsItemRepository reservationGoodsItemRepository;
    private final UserRepository userRepository;
    private final PaymentComplaintRepository paymentComplaintRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.integration.DuongFileEvidence> duongEvidence;

    @Transactional
    public FileAssetResponse store(ActorPrincipal actor, MultipartFile file, String entityType, UUID entityId) {
        validate(file);
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        String contentType = file.getContentType().toLowerCase(java.util.Locale.ROOT);
        String safeOriginalName = safeOriginalName(file.getOriginalFilename());
        String normalizedEntityType = normalizeEntityType(entityType);
        validateEntityOwnership(actor, normalizedEntityType, entityId, file.getContentType());
        UUID fileId = UUID.randomUUID();
        Path root = Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
        Path temporary = root.resolve(fileId + ".uploading");
        Path target = root.resolve(fileId + EXTENSIONS.get(contentType));

        try {
            Files.createDirectories(root);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            validateMagicBytes(temporary, contentType);
            String checksum = sha256(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            FileAsset asset = new FileAsset();
            asset.setUploadedBy(user);
            asset.setStorageKey(target.toString());
            asset.setOriginalName(safeOriginalName);
            asset.setContentType(contentType);
            asset.setSizeBytes(file.getSize());
            asset.setChecksumSha256(checksum);
            boolean privateSupportUpload=com.storagehub.service.integration.DuongFileEvidence.UPLOAD.equals(normalizedEntityType)
                ||com.storagehub.service.integration.DuongFileEvidence.PUBLIC.equals(normalizedEntityType)&&entityId==null;
            asset.setEntityType(privateSupportUpload?com.storagehub.service.integration.DuongFileEvidence.UPLOAD:normalizedEntityType);
            asset.setEntityId(privateSupportUpload?actor.userId():entityId);
            FileAsset saved = fileAssetRepository.saveAndFlush(asset);
            FileAssetResponse response = toResponse(saved);
            auditLogService.recordMutation(user, "FILE_UPLOADED", "FileAsset", saved.getId(), null, null, response);
            return response;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw new IllegalStateException("File storage failed", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(ActorPrincipal actor, UUID fileId) {
        FileAsset asset = fileAssetRepository.findById(fileId)
            .orElseThrow(() -> ApiExceptions.notFound("File was not found"));
        requireDownloadAccess(actor, asset);
        try {
            Path path = Path.of(asset.getStorageKey()).toAbsolutePath().normalize();
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw ApiExceptions.notFound("File was not found");
            }
            return new DownloadedFile(
                resource, asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
                asset.getChecksumSha256()
            );
        } catch (java.net.MalformedURLException exception) {
            throw ApiExceptions.notFound("File was not found");
        }
    }

    private void requireDownloadAccess(ActorPrincipal actor, FileAsset asset) {
        if(com.storagehub.service.integration.DuongFileEvidence.handles(asset.getEntityType())) {
            duongEvidence().requireDownload(actor,asset);
            return; // Deliberately before uploader shortcut, so INTERNAL never leaks to Customer.
        }
        if (asset.getUploadedBy().getId().equals(actor.userId())) {
            return;
        }
        if ("CHECK_IN".equals(asset.getEntityType()) && asset.getEntityId() != null) {
            var checkIn = checkInRepository.findById(asset.getEntityId())
                .orElseThrow(() -> ApiExceptions.notFound("File was not found"));
            authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
            facilityScopeService.assertCanRead(actor, checkIn.getReservation().getFacility().getId());
            return;
        }
        if ("PAYMENT_COMPLAINT".equals(asset.getEntityType()) && asset.getEntityId() != null) {
            PaymentComplaint complaint = paymentComplaintRepository.findById(asset.getEntityId())
                .orElseThrow(() -> ApiExceptions.notFound("File was not found"));
            boolean manager = actor.roles().contains(RoleCode.MANAGER)
                || actor.roles().contains(RoleCode.BUSINESS)
                || actor.roles().contains(RoleCode.ADMIN);
            if (manager) {
                authorizationService.require(actor, SystemPermission.VIEW_PAYMENTS);
                facilityScopeService.assertCanRead(actor, complaint.getReservation().getFacility().getId());
                return;
            }
        }
        throw ApiExceptions.notFound("File was not found");
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiExceptions.validation("A non-empty file is required", null);
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw ApiExceptions.validation("File exceeds the configured size limit", Map.of("maxSizeBytes", properties.getMaxSizeBytes()));
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType().toLowerCase(java.util.Locale.ROOT))) {
            throw ApiExceptions.validation("File type is not allowed", Map.of("allowedTypes", ALLOWED_TYPES));
        }
    }

    private String safeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "upload";
        }
        String name = Path.of(originalName).getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        return name.substring(0, Math.min(name.length(), 255));
    }

    private String normalizeEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return null;
        }
        String normalized = entityType.trim().toUpperCase(java.util.Locale.ROOT);
        if(com.storagehub.service.integration.DuongFileEvidence.handles(normalized))return normalized;
        if (!Set.of("CONTRACT", "CHECK_IN", "RETURN_CASE", "SUPPORT_TICKET", "RESERVATION_GOODS_ITEM", "OTHER").contains(normalized)) {
            throw ApiExceptions.validation("entityType is not supported", null);
        }
        return normalized;
    }

    private void validateEntityOwnership(
        ActorPrincipal actor,
        String entityType,
        UUID entityId,
        String contentType
    ) {
        if(com.storagehub.service.integration.DuongFileEvidence.handles(entityType)) {
            duongEvidence().requireUpload(actor,entityType,entityId);
            return;
        }
        if ("CHECK_IN".equals(entityType)) {
            if (entityId == null) {
                throw ApiExceptions.validation("entityId is required for check-in evidence", null);
            }
            authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
            var checkIn = checkInRepository.findById(entityId)
                .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
            facilityScopeService.assertCanOperate(actor, checkIn.getReservation().getFacility().getId());
            if (checkIn.getStatus() != CheckInStatus.scheduled) {
                throw ApiExceptions.conflict("Evidence can only be uploaded for a scheduled check-in");
            }
            return;
        }
        if (!"RESERVATION_GOODS_ITEM".equals(entityType)) {
            return;
        }
        if (entityId == null) {
            throw ApiExceptions.validation("entityId is required for a goods image", null);
        }
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw ApiExceptions.validation("Goods attachments must be images", null);
        }

        ReservationGoodsItem goodsItem = reservationGoodsItemRepository.findById(entityId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation goods item was not found"));
        if (!goodsItem.getReservation().getCustomer().getId().equals(actor.userId())) {
            throw ApiExceptions.forbidden("The goods item does not belong to the current customer");
        }
    }

    private com.storagehub.service.integration.DuongFileEvidence duongEvidence() {
        var source=duongEvidence==null?null:duongEvidence.getIfAvailable();
        if(source==null)throw ApiExceptions.conflict("DEFERRED_SOURCE: Evidence access source is missing");
        return source;
    }
    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validateMagicBytes(Path path, String contentType) throws IOException {
        byte[] header = new byte[12];
        int length;
        try (InputStream input = Files.newInputStream(path)) {
            length = input.read(header);
        }
        boolean valid = switch (contentType) {
            case "image/jpeg" -> length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
            case "image/png" -> length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47;
            case "image/webp" -> length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            case "application/pdf" -> length >= 4 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F';
            default -> false;
        };
        if (!valid) {
            throw ApiExceptions.validation("File content does not match its declared type", null);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private FileAssetResponse toResponse(FileAsset asset) {
        return new FileAssetResponse(
            asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
            asset.getChecksumSha256(), asset.getEntityType(), asset.getEntityId(), asset.getStatus(), asset.getCreatedAt()
        );
    }

    public record DownloadedFile(
        Resource resource,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256
    ) {
    }
}
