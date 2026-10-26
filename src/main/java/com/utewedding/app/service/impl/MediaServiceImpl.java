package com.utewedding.app.service.impl;

import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.dto.response.MediaUploadResponse;
import com.utewedding.app.integration.CloudinaryService;
import com.utewedding.app.integration.CloudinaryUploadResult;
import com.utewedding.app.repository.MediaAssetRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.service.MediaService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MediaServiceImpl implements MediaService {

    private final CloudinaryService cloudinaryService;
    private final WeddingRepository weddingRepository;
    private final Validator validator;
    private final MediaAssetPersistenceService persistenceService;

    /** Constructor retained for focused unit tests and existing integrations. */
    public MediaServiceImpl(CloudinaryService cloudinaryService,
                            MediaAssetRepository mediaAssetRepository,
                            WeddingRepository weddingRepository,
                            Validator validator) {
        this(cloudinaryService, weddingRepository, validator,
                new MediaAssetPersistenceService(mediaAssetRepository, weddingRepository));
    }

    @Autowired
    public MediaServiceImpl(CloudinaryService cloudinaryService,
                            WeddingRepository weddingRepository,
                            Validator validator,
                            MediaAssetPersistenceService persistenceService) {
        this.cloudinaryService = cloudinaryService;
        this.weddingRepository = weddingRepository;
        this.validator = validator;
        this.persistenceService = persistenceService;
    }

    @Override
    public MediaUploadResponse upload(UUID ownerId, MediaUploadRequest request) {
        if (ownerId == null) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Yêu cầu đăng nhập.");
        }
        Set<ConstraintViolation<MediaUploadRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        // Resolve through the owner-scoped query so a caller cannot upload into
        // another user's wedding by changing only the weddingId form field.
        weddingRepository.findByIdAndOwnerId(request.weddingId(), ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));

        CloudinaryUploadResult upload = cloudinaryService.uploadWeddingImage(
                request.file(), request.weddingId().toString(), request.kind().name());
        // Cloudinary I/O is deliberately complete before the short DB transaction.
        return persistenceService.persist(ownerId, request, upload);
    }
}
