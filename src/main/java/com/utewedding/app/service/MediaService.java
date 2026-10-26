package com.utewedding.app.service;

import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.dto.response.MediaUploadResponse;
import java.util.UUID;

public interface MediaService {
    MediaUploadResponse upload(UUID ownerId, MediaUploadRequest request);
}
