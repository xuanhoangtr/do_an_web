package com.utewedding.app.controller;

import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.dto.request.MediaUploadRequest;
import com.utewedding.app.dto.response.MediaUploadResponse;
import com.utewedding.app.service.MediaService;
import com.utewedding.app.service.web.CurrentUserContext;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Multipart media endpoint. The returned url is the Cloudinary secure_url. */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService mediaService;
    private final CurrentUserContext currentUser;

    public MediaController(MediaService mediaService, CurrentUserContext currentUser) {
        this.mediaService = mediaService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public MediaUploadResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam UUID weddingId,
            @RequestParam(defaultValue = "GALLERY") MediaAssetKind kind,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) String caption,
            Authentication authentication) {
        return mediaService.upload(currentUser.requireUserId(authentication),
                new MediaUploadRequest(file, weddingId, kind, altText, caption));
    }
}
