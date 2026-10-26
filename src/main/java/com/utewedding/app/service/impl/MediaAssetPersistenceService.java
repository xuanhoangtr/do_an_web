package com.utewedding.app.service.impl;

import com.utewedding.app.domain.MediaAssetStatus;
import com.utewedding.app.domain.entity.MediaAssetEntity;
import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.dto.response.MediaUploadResponse;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.integration.CloudinaryUploadResult;
import com.utewedding.app.repository.MediaAssetRepository;
import com.utewedding.app.repository.WeddingRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persists an already-uploaded media object in a short, rollback-safe transaction. */
@Service
public class MediaAssetPersistenceService {

    private final MediaAssetRepository mediaAssetRepository;
    private final WeddingRepository weddingRepository;

    public MediaAssetPersistenceService(MediaAssetRepository mediaAssetRepository,
                                        WeddingRepository weddingRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.weddingRepository = weddingRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaUploadResponse persist(UUID ownerId, MediaUploadRequest request,
                                       CloudinaryUploadResult upload) {
        weddingRepository.findByIdAndOwnerId(request.weddingId(), ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));

        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setWeddingId(request.weddingId());
        asset.setKind(request.kind());
        asset.setStatus(MediaAssetStatus.READY);
        asset.setObjectKey(upload.secureUrl());
        asset.setPublicId(upload.publicId());
        asset.setMimeType(request.file().getContentType());
        asset.setByteSize(toPersistedByteSize(request.file().getSize()));
        asset.setWidth(upload.width());
        asset.setHeight(upload.height());
        asset.setAltText(normalise(request.altText()));
        asset.setCaption(normalise(request.caption()));
        asset = mediaAssetRepository.save(asset);

        return new MediaUploadResponse(asset.getId(), asset.getWeddingId(), asset.getKind(),
                asset.getStatus(), asset.getPublicId(), asset.getSecureUrl(), upload.resourceType(),
                upload.format(), asset.getWidth(), asset.getHeight(), upload.bytes());
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int toPersistedByteSize(long byteSize) {
        if (byteSize < 0 || byteSize > Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Kích thước tệp vượt quá giới hạn lưu trữ.");
        }
        return (int) byteSize;
    }
}
