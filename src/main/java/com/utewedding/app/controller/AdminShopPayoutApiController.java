package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.dto.request.ShopPayoutCancelCommand;
import com.utewedding.app.dto.request.ShopPayoutMarkPaidCommand;
import com.utewedding.app.dto.request.ShopPayoutVerifyDestinationCommand;
import com.utewedding.app.dto.response.ShopPayoutBankInstructions;
import com.utewedding.app.dto.response.ShopPayoutDestinationView;
import com.utewedding.app.dto.response.ShopPayoutView;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.CloudinaryPrivateMediaProxy;
import com.utewedding.app.service.impl.ShopPayoutService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

/** Admin maker/checker workflow for manually transferring shop settlement funds. */
@RestController
@RequestMapping("/api/v1/admin/finance/payouts")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShopPayoutApiController {
    private final ShopPayoutService payouts;
    private final MarketplaceFeatureFlags features;

    public AdminShopPayoutApiController(ShopPayoutService payouts, MarketplaceFeatureFlags features) {
        this.payouts = payouts;
        this.features = features;
    }

    @GetMapping
    public ResponseEntity<List<ShopPayoutView>> list(@AuthenticationPrincipal UserPrincipal actor) {
        return privateResponse(features.isEnabled(Feature.PAYOUT)
                ? payouts.adminPayouts(actor.getId()) : payouts.adminProcessingPayouts(actor.getId()));
    }

    @GetMapping("/{payoutId}")
    public ResponseEntity<ShopPayoutView> detail(@AuthenticationPrincipal UserPrincipal actor,
                                                 @PathVariable UUID payoutId) {
        return privateResponse(features.isEnabled(Feature.PAYOUT)
                ? payouts.adminPayout(actor.getId(), payoutId)
                : payouts.adminProcessingPayout(actor.getId(), payoutId));
    }

    @GetMapping("/destinations/pending")
    public ResponseEntity<List<ShopPayoutDestinationView>> pendingDestinations(
            @AuthenticationPrincipal UserPrincipal actor) {
        requirePayoutEnabled();
        return privateResponse(payouts.adminPendingDestinations(actor.getId()));
    }

    @GetMapping("/destinations/{destinationId}/instructions")
    public ResponseEntity<ShopPayoutBankInstructions> destinationInstructions(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID destinationId,
            HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.revealDestination(actor.getId(), destinationId, context(request, actor)));
    }

    @PostMapping("/destinations/{destinationId}/verify")
    public ResponseEntity<ShopPayoutDestinationView> verifyDestination(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID destinationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShopPayoutVerifyDestinationCommand command, HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.verifyDestination(actor.getId(), destinationId, idempotencyKey,
                command, context(request, actor)));
    }

    @PostMapping(value = "/proofs", consumes = "multipart/form-data")
    public ResponseEntity<ProofUploadResponse> uploadProof(@AuthenticationPrincipal UserPrincipal actor,
            @RequestPart("file") MultipartFile file) {
        requirePayoutEnabled();
        ManagedMediaObjectEntity media = payouts.uploadPayoutProof(actor, file);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new ProofUploadResponse(media.getId(), media.getPurpose()));
    }

    @PostMapping(value = "/{payoutId}/proof", consumes = "multipart/form-data")
    public ResponseEntity<ProofUploadResponse> uploadProcessingProof(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, @RequestPart("file") MultipartFile file) {
        if (features.isEnabled(Feature.PAYOUT)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ManagedMediaObjectEntity media = payouts.uploadPayoutProofForProcessing(actor, payoutId, file);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new ProofUploadResponse(media.getId(), media.getPurpose()));
    }

    @PostMapping("/{payoutId}/approve")
    public ResponseEntity<ShopPayoutView> approve(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.approve(actor.getId(), payoutId, idempotencyKey, context(request, actor)));
    }

    @PostMapping("/{payoutId}/process")
    public ResponseEntity<ShopPayoutView> process(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.beginProcessing(actor.getId(), payoutId, idempotencyKey,
                context(request, actor)));
    }

    @PostMapping("/{payoutId}/paid")
    public ResponseEntity<ShopPayoutView> paid(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShopPayoutMarkPaidCommand command, HttpServletRequest request) {
        // Keep this reconciliation action available during a rollout pause so
        // an Admin can record an already completed transfer. ShopPayoutService
        // enforces PROCESSING state, Admin role, and idempotency.
        return privateResponse(payouts.markPaid(actor.getId(), payoutId, idempotencyKey, command,
                context(request, actor)));
    }

    @PostMapping("/{payoutId}/cancel")
    public ResponseEntity<ShopPayoutView> cancel(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShopPayoutCancelCommand command, HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.cancel(actor.getId(), payoutId, idempotencyKey,
                command == null ? null : command.reason(), context(request, actor)));
    }

    @GetMapping("/{payoutId}/instructions")
    public ResponseEntity<ShopPayoutBankInstructions> instructions(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable UUID payoutId, HttpServletRequest request) {
        requirePayoutEnabled();
        return privateResponse(payouts.revealPayoutInstructions(actor.getId(), payoutId, context(request, actor)));
    }

    @GetMapping("/{payoutId}/proof")
    public ResponseEntity<StreamingResponseBody> proof(@AuthenticationPrincipal UserPrincipal actor,
                                                        @PathVariable UUID payoutId) {
        requirePayoutEnabled();
        ShopPayoutService.PayoutProofDownload download = payouts.payoutProof(actor.getId(), payoutId);
        CloudinaryPrivateMediaProxy.OpenedDownload stream = download.stream();
        StreamingResponseBody body = output -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                long written = 0;
                int count;
                while ((count = stream.inputStream().read(buffer)) != -1) {
                    written += count;
                    if (written > stream.maxBytes()) throw new IOException("Media exceeds the configured limit.");
                    output.write(buffer, 0, count);
                }
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(stream.contentType()));
        if (stream.contentLength() >= 0) response.contentLength(stream.contentLength());
        return response.body(body);
    }

    private void requirePayoutEnabled() {
        if (!features.isEnabled(Feature.PAYOUT)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static RequestContext context(HttpServletRequest request, UserPrincipal user) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), user.getRole().name());
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ConflictResponse> duplicateIntegrityConstraint(DataIntegrityViolationException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ConflictResponse("PAYOUT_CONFLICT", "Dữ liệu payout đã được ghi nhận hoặc bị trùng."));
    }

    public record ProofUploadResponse(UUID mediaId, String purpose) { }
    public record ConflictResponse(String code, String message) { }
}
