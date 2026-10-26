package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.integration.CloudinaryService;
import com.utewedding.app.integration.CloudinaryUploadResult;
import com.utewedding.app.integration.ManagedMediaMetadata;
import com.utewedding.app.integration.ManagedMediaValidator;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ManagedMediaService {
    private static final Set<String> PRIVATE_PURPOSES = Set.of(
            "RETURN_EVIDENCE", "SUPPORT_ATTACHMENT", "DELIVERY_PROOF", "SHOP_APPLICATION_EVIDENCE",
            "PAYOUT_PROOF");
    private static final Set<String> GENERIC_PRIVATE_PURPOSES = Set.of(
            "RETURN_EVIDENCE", "SUPPORT_ATTACHMENT", "DELIVERY_PROOF", "PAYOUT_PROOF");
    private static final Set<String> SHOP_PUBLIC_PURPOSES = Set.of("SHOP_LOGO", "SHOP_BANNER");
    private final ManagedMediaObjectRepository repository;
    private final CloudinaryService cloudinary;
    private final ManagedMediaValidator validator;
    private final TransactionTemplate transactions;
    @Value("${media.retention.evidence-days:2555}")
    private int evidenceRetentionDays;

    public ManagedMediaService(ManagedMediaObjectRepository repository, CloudinaryService cloudinary,
                               ManagedMediaValidator validator, TransactionTemplate transactions) {
        this.repository = repository;
        this.cloudinary = cloudinary;
        this.validator = validator;
        this.transactions = transactions;
    }

    /** Network upload runs before the short metadata transaction. */
    public ManagedMediaObjectEntity upload(MultipartFile file, UUID ownerId, String purpose,
                                            Duration retention) {
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        if (!GENERIC_PRIVATE_PURPOSES.contains(normalized)) {
            throw new BusinessConflictException("Media purpose không được hỗ trợ.");
        }
        ManagedMediaMetadata metadata = validator.validate(file);
        var uploaded = cloudinary.uploadImage(file, ownerId == null ? "system" : ownerId.toString(),
                normalized.toLowerCase(Locale.ROOT), true);
        try {
            byte[] bytes = file.getBytes();
            return transactions.execute(status -> {
                ManagedMediaObjectEntity entity = new ManagedMediaObjectEntity();
                entity.setPublicId(uploaded.publicId());
                entity.setSecureUrl(uploaded.secureUrl());
                entity.setResourceType(uploaded.resourceType());
                entity.setDeliveryType("authenticated");
                entity.setDetectedMime(metadata.mimeType());
                entity.setDetectedFormat(metadata.format());
                entity.setByteSize(metadata.bytes());
                entity.setWidth(metadata.width());
                entity.setHeight(metadata.height());
                entity.setPixelCount(metadata.pixelCount());
                entity.setPurpose(normalized);
                entity.setOwnerId(ownerId);
                entity.setStatus(ManagedMediaStatus.READY);
                Duration minimumRetention = minimumRetention(normalized);
                Duration requestedRetention = retention == null ? minimumRetention : retention;
                entity.setRetainUntil(Instant.now().plus(requestedRetention.compareTo(minimumRetention) < 0
                        ? minimumRetention : requestedRetention));
                entity.setSha256(sha(bytes));
                return repository.saveAndFlush(entity);
            });
        } catch (Exception exception) {
            CloudinaryService.DeleteResult deleted = cloudinary.deleteImage(
                    uploaded.publicId(), uploaded.resourceType(), "authenticated");
            if (deleted == CloudinaryService.DeleteResult.ERROR) {
                try {
                    persistOrphanForCleanup(file, ownerId, uploaded, metadata, normalized, "authenticated");
                } catch (RuntimeException ignored) {
                    // Preserve the upload failure; the orphan can be reconciled if storage recovers.
                }
            }
            throw new BusinessConflictException("Không thể lưu metadata media; upload đã được thu hồi.");
        }
    }

    /**
     * Uploads shop-scoped media and commits its association together with the caller's
     * authorized shop mutation. The callback runs after the media row has an id and
     * inside the same transaction; a failed policy check rolls back both rows.
     */
    ManagedMediaObjectEntity uploadForShop(MultipartFile file, UUID ownerId, UUID shopId,
                                           UUID applicationId, String purpose,
                                           Consumer<ManagedMediaObjectEntity> authorizedMutation) {
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        boolean privateDelivery = "SHOP_APPLICATION_EVIDENCE".equals(normalized);
        if ((!privateDelivery && !SHOP_PUBLIC_PURPOSES.contains(normalized))
                || shopId == null || ownerId == null
                || (privateDelivery && applicationId == null)
                || (!privateDelivery && applicationId != null)
                || authorizedMutation == null) {
            throw new BusinessConflictException("Loại media cửa hàng không hợp lệ.");
        }

        ManagedMediaMetadata metadata = validator.validate(file);
        String deliveryType = privateDelivery ? "authenticated" : "upload";
        CloudinaryUploadResult uploaded = cloudinary.uploadImage(
                file, shopId.toString(), normalized.toLowerCase(Locale.ROOT), privateDelivery);
        try {
            byte[] bytes = file.getBytes();
            ManagedMediaObjectEntity saved = transactions.execute(status -> {
                ManagedMediaObjectEntity entity = new ManagedMediaObjectEntity();
                entity.setPublicId(uploaded.publicId());
                entity.setSecureUrl(uploaded.secureUrl());
                entity.setResourceType(uploaded.resourceType());
                entity.setDeliveryType(deliveryType);
                entity.setDetectedMime(metadata.mimeType());
                entity.setDetectedFormat(metadata.format());
                entity.setByteSize(uploaded.bytes() == null ? metadata.bytes() : uploaded.bytes());
                entity.setWidth(uploaded.width() == null ? metadata.width() : uploaded.width());
                entity.setHeight(uploaded.height() == null ? metadata.height() : uploaded.height());
                entity.setPixelCount(Math.multiplyExact((long) entity.getWidth(), entity.getHeight()));
                entity.setSha256(sha(bytes));
                entity.setPurpose(normalized);
                entity.setOwnerId(ownerId);
                entity.setShopId(shopId);
                entity.setApplicationId(applicationId);
                entity.setStatus(ManagedMediaStatus.READY);
                entity.setRetainUntil(Instant.now().plus(minimumRetention(normalized)));
                entity = repository.saveAndFlush(entity);
                authorizedMutation.accept(entity);
                return entity;
            });
            if (saved == null) throw new IllegalStateException("Shop media transaction returned no record.");
            return saved;
        } catch (RuntimeException exception) {
            cleanupFailedShopUpload(file, ownerId, shopId, applicationId, uploaded, metadata,
                    normalized, deliveryType);
            throw exception;
        } catch (Exception exception) {
            cleanupFailedShopUpload(file, ownerId, shopId, applicationId, uploaded, metadata,
                    normalized, deliveryType);
            throw new BusinessConflictException("Không thể lưu media cửa hàng.");
        }
    }

    private void cleanupFailedShopUpload(MultipartFile file, UUID ownerId, UUID shopId, UUID applicationId,
                                         CloudinaryUploadResult uploaded, ManagedMediaMetadata metadata,
                                         String purpose, String deliveryType) {
        CloudinaryService.DeleteResult deleted = cloudinary.deleteImage(
                uploaded.publicId(), uploaded.resourceType(), deliveryType);
        if (deleted == CloudinaryService.DeleteResult.ERROR) {
            try {
                persistOrphanForCleanup(file, ownerId, uploaded, metadata, purpose, deliveryType,
                        shopId, applicationId);
            } catch (RuntimeException ignored) {
                // Preserve the failed operation; a later storage reconciliation can retry.
            }
        }
    }

    /** Persists cleanup metadata for a public image that has already been uploaded. */
    public ManagedMediaObjectEntity trackPublicReviewUpload(MultipartFile file, UUID ownerId,
                                                             CloudinaryUploadResult uploaded) {
        ManagedMediaMetadata metadata = validator.validate(file);
        try {
            byte[] bytes = file.getBytes();
            return transactions.execute(status -> {
                ManagedMediaObjectEntity entity = new ManagedMediaObjectEntity();
                entity.setPublicId(uploaded.publicId());
                entity.setSecureUrl(uploaded.secureUrl());
                entity.setResourceType(uploaded.resourceType());
                entity.setDeliveryType("upload");
                entity.setDetectedMime(metadata.mimeType());
                entity.setDetectedFormat(metadata.format());
                entity.setByteSize(uploaded.bytes() == null ? metadata.bytes() : uploaded.bytes());
                entity.setWidth(uploaded.width() == null ? metadata.width() : uploaded.width());
                entity.setHeight(uploaded.height() == null ? metadata.height() : uploaded.height());
                entity.setPixelCount(Math.multiplyExact((long) entity.getWidth(), entity.getHeight()));
                entity.setPurpose("REVIEW");
                entity.setOwnerId(ownerId);
                entity.setStatus(ManagedMediaStatus.READY);
                entity.setSha256(sha(bytes));
                return repository.saveAndFlush(entity);
            });
        } catch (Exception exception) {
            CloudinaryService.DeleteResult deleted = cloudinary.deleteImage(
                    uploaded.publicId(), uploaded.resourceType(), "upload");
            if (deleted == CloudinaryService.DeleteResult.ERROR) {
                try {
                    persistOrphanForCleanup(file, ownerId, uploaded, metadata, "REVIEW", "upload");
                } catch (RuntimeException ignored) {
                    // The database is unavailable; the cleanup job can only retry once metadata can be persisted.
                }
            }
            throw new BusinessConflictException("Không thể lưu metadata media đánh giá.");
        }
    }

    /** Immediately queues deletion for an upload whose owning review transaction rolled back. */
    public void abortPublicReviewUpload(UUID mediaId) {
        transactions.executeWithoutResult(status -> repository.findById(mediaId).ifPresent(media -> {
            if (!"REVIEW".equals(media.getPurpose()) || media.isLegalHold()
                    || media.getStatus() == ManagedMediaStatus.DELETED) return;
            Instant now = Instant.now();
            media.setStatus(ManagedMediaStatus.DELETE_PENDING);
            media.setRetainUntil(now);
            media.setNextRetryAt(now);
        }));
    }

    /** Queues cleanup when a payout proof upload cannot be attached to its processing payout. */
    public void abortUnattachedPayoutProofUpload(UUID mediaId, UUID ownerId) {
        transactions.executeWithoutResult(status -> repository.findById(mediaId).ifPresent(media -> {
            if (!"PAYOUT_PROOF".equals(media.getPurpose()) || !ownerId.equals(media.getOwnerId())
                    || media.isLegalHold() || media.getStatus() != ManagedMediaStatus.READY) return;
            Instant now = Instant.now();
            media.setStatus(ManagedMediaStatus.DELETE_PENDING);
            media.setRetainUntil(now);
            media.setNextRetryAt(now);
        }));
    }

    /** Tracks a public product image so failed product writes can queue Cloudinary cleanup. */
    public ManagedMediaObjectEntity trackPublicProductUpload(MultipartFile file, UUID ownerId,
                                                              CloudinaryUploadResult uploaded) {
        return trackPublicProductUpload(file, ownerId, null, uploaded);
    }

    /** Tracks a product image against the shop that is allowed to attach it. */
    public ManagedMediaObjectEntity trackPublicProductUpload(MultipartFile file, UUID ownerId, UUID shopId,
                                                              CloudinaryUploadResult uploaded) {
        ManagedMediaMetadata metadata = validator.validateProductImage(file);
        try {
            byte[] bytes = file.getBytes();
            return transactions.execute(status -> {
                ManagedMediaObjectEntity entity = new ManagedMediaObjectEntity();
                entity.setPublicId(uploaded.publicId());
                entity.setSecureUrl(uploaded.secureUrl());
                entity.setResourceType(uploaded.resourceType());
                entity.setDeliveryType("upload");
                entity.setDetectedMime(metadata.mimeType());
                entity.setDetectedFormat(metadata.format());
                entity.setByteSize(uploaded.bytes() == null ? metadata.bytes() : uploaded.bytes());
                entity.setWidth(uploaded.width() == null ? metadata.width() : uploaded.width());
                entity.setHeight(uploaded.height() == null ? metadata.height() : uploaded.height());
                entity.setPixelCount(Math.multiplyExact((long) entity.getWidth(), entity.getHeight()));
                entity.setPurpose("PRODUCT");
                entity.setOwnerId(ownerId);
                entity.setShopId(shopId);
                entity.setStatus(ManagedMediaStatus.READY);
                entity.setSha256(sha(bytes));
                return repository.saveAndFlush(entity);
            });
        } catch (Exception exception) {
            CloudinaryService.DeleteResult deleted = cloudinary.deleteImage(
                    uploaded.publicId(), uploaded.resourceType(), "upload");
            if (deleted == CloudinaryService.DeleteResult.ERROR) {
                try {
                    persistOrphanForCleanup(file, ownerId, uploaded, metadata, "PRODUCT", "upload", shopId, null);
                } catch (RuntimeException ignored) {
                    // Preserve the metadata error; the orphan can be reconciled when storage recovers.
                }
            }
            throw new BusinessConflictException("Không thể lưu metadata ảnh sản phẩm.");
        }
    }

    /** Queues deletion when a product transaction fails after the image was uploaded. */
    public void abortPublicProductUpload(UUID mediaId) {
        transactions.executeWithoutResult(status -> repository.findById(mediaId).ifPresent(media -> {
            if (!"PRODUCT".equals(media.getPurpose()) || media.isLegalHold()
                    || media.getStatus() == ManagedMediaStatus.DELETED) return;
            Instant now = Instant.now();
            media.setStatus(ManagedMediaStatus.DELETE_PENDING);
            media.setRetainUntil(now);
            media.setNextRetryAt(now);
        }));
    }

    private void persistOrphanForCleanup(MultipartFile file, UUID ownerId, CloudinaryUploadResult uploaded,
                                         ManagedMediaMetadata metadata, String purpose, String deliveryType) {
        persistOrphanForCleanup(file, ownerId, uploaded, metadata, purpose, deliveryType, null, null);
    }

    private void persistOrphanForCleanup(MultipartFile file, UUID ownerId, CloudinaryUploadResult uploaded,
                                         ManagedMediaMetadata metadata, String purpose, String deliveryType,
                                         UUID shopId, UUID applicationId) {
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        transactions.execute(status -> {
            ManagedMediaObjectEntity entity = new ManagedMediaObjectEntity();
            entity.setPublicId(uploaded.publicId());
            entity.setSecureUrl(uploaded.secureUrl());
            entity.setResourceType(uploaded.resourceType());
            entity.setDeliveryType(deliveryType);
            entity.setDetectedMime(metadata.mimeType());
            entity.setDetectedFormat(metadata.format());
            entity.setByteSize(uploaded.bytes() == null ? metadata.bytes() : uploaded.bytes());
            entity.setWidth(uploaded.width() == null ? metadata.width() : uploaded.width());
            entity.setHeight(uploaded.height() == null ? metadata.height() : uploaded.height());
            entity.setPixelCount(Math.multiplyExact((long) entity.getWidth(), entity.getHeight()));
            entity.setPurpose(purpose);
            entity.setOwnerId(ownerId);
            entity.setShopId(shopId);
            entity.setApplicationId(applicationId);
            entity.setStatus(ManagedMediaStatus.DELETE_PENDING);
            entity.setRetainUntil(Instant.now());
            entity.setNextRetryAt(Instant.now());
            entity.setSha256(sha(bytes));
            return repository.saveAndFlush(entity);
        });
    }

    public String signedUrl(UUID mediaId) {
        ManagedMediaObjectEntity media = repository.findById(mediaId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy media."));
        if (media.getStatus() != ManagedMediaStatus.READY
                || !"authenticated".equals(media.getDeliveryType())) {
            throw new BusinessConflictException("Media không khả dụng.");
        }
        return cloudinary.signedPrivateUrl(media.getPublicId(), media.getDetectedFormat());
    }

    public void requestDelete(UUID mediaId) {
        transactions.executeWithoutResult(status -> repository.findById(mediaId).ifPresent(media -> {
            if (!media.isLegalHold() && media.getStatus() != ManagedMediaStatus.DELETED) {
                Instant policyRetention = media.getCreatedAt() == null
                        ? Instant.now().plus(minimumRetention(media.getPurpose()))
                        : media.getCreatedAt().plus(minimumRetention(media.getPurpose()));
                if (media.getRetainUntil() == null || media.getRetainUntil().isBefore(policyRetention)) {
                    media.setRetainUntil(policyRetention);
                }
                media.setStatus(ManagedMediaStatus.DELETE_PENDING);
                media.setNextRetryAt(Instant.now());
            }
        }));
    }

    @Scheduled(fixedDelayString = "${media.cleanup.fixed-delay-ms:60000}",
            initialDelayString = "${media.cleanup.initial-delay-ms:60000}")
    @SchedulerLock(name = "managed-media-cleanup", lockAtMostFor = "10m")
    public void cleanup() {
        List<ManagedMediaObjectEntity> claimed = transactions.execute(status -> {
            List<ManagedMediaObjectEntity> rows = repository.claimCleanup(
                    List.of("DELETE_PENDING", "FAILED"), Instant.now(), 20);
            rows.forEach(media -> media.setStatus(ManagedMediaStatus.DELETING));
            return List.copyOf(rows);
        });
        if (claimed == null) return;
        for (ManagedMediaObjectEntity media : claimed) {
            try {
                CloudinaryService.DeleteResult result = cloudinary.deleteImage(
                        media.getPublicId(), media.getResourceType(), media.getDeliveryType());
                if (result == CloudinaryService.DeleteResult.ERROR) {
                    throw new IllegalStateException("Cloudinary deletion did not confirm removal.");
                }
                transactions.executeWithoutResult(status -> repository.findById(media.getId()).ifPresent(current -> {
                    current.setStatus(ManagedMediaStatus.DELETED);
                    current.setDeletedAt(Instant.now());
                    current.setSecureUrl(null);
                }));
            } catch (RuntimeException exception) {
                transactions.executeWithoutResult(status -> repository.findById(media.getId()).ifPresent(current -> {
                    current.setStatus(ManagedMediaStatus.DELETE_PENDING);
                    current.setRetryCount(current.getRetryCount() + 1);
                    String message = exception.getMessage() == null ? "cleanup failed" : exception.getMessage();
                    current.setLastError(message.substring(0, Math.min(1000, message.length())));
                    current.setNextRetryAt(Instant.now().plusSeconds(
                            Math.min(3600, 30L << Math.min(6, current.getRetryCount()))));
                }));
            }
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Duration minimumRetention(String purpose) {
        return PRIVATE_PURPOSES.contains(purpose) ? Duration.ofDays(Math.max(0, evidenceRetentionDays))
                : Duration.ofDays(30);
    }
}
