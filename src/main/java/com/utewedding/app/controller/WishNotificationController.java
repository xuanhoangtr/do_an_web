package com.utewedding.app.controller;

import com.utewedding.app.dto.request.SubmitWishRequest;
import com.utewedding.app.dto.response.WishCreatedResponse;
import com.utewedding.app.service.PublicWishService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Persists a public guest wish and emits the real-time wedding bell event. */
@RestController
@RequestMapping("/api/v1/weddings/{weddingId}/wishes")
public class WishNotificationController {

    private final PublicWishService publicWishService;

    public WishNotificationController(PublicWishService publicWishService) {
        this.publicWishService = publicWishService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WishCreatedResponse createWish(
            @PathVariable UUID weddingId,
            @Valid @RequestBody SubmitWishRequest request) {
        return publicWishService.submit(weddingId, request);
    }
}
