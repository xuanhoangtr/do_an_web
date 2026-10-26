package com.utewedding.app.dto.request;

import com.utewedding.app.domain.MediaAssetKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/** Request DTO for an image upload; persistence stays inside the media service. */
public record MediaUploadRequest(
        @NotNull MultipartFile file,
        @NotNull UUID weddingId,
        @NotNull MediaAssetKind kind,
        @Size(max = 300) String altText,
        @Size(max = 500) String caption) {
}
