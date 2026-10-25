package com.utewedding.app.controller.vendor.api;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.dto.request.ShopPayoutDestinationCommand;
import com.utewedding.app.dto.request.ShopPayoutRequestCommand;
import com.utewedding.app.dto.response.ShopPayoutDestinationView;
import com.utewedding.app.dto.response.ShopOwnerPayoutView;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.ShopPayoutService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Private owner-only endpoints for a shop bank destination and payout requests. */
@RestController
@RequestMapping("/api/v1/vendor/finance")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorPayoutApiController {
    private final ShopPayoutService payouts;
    private final MarketplaceFeatureFlags features;

    public VendorPayoutApiController(ShopPayoutService payouts, MarketplaceFeatureFlags features) {
        this.payouts = payouts;
        this.features = features;
    }

    @GetMapping("/payout-destinations")
    public ResponseEntity<List<ShopPayoutDestinationView>> destinations(@AuthenticationPrincipal UserPrincipal owner) {
        requirePayoutEnabled();
        return privateResponse(payouts.ownerDestinations(owner));
    }

    @PostMapping("/payout-destinations")
    public ResponseEntity<ShopPayoutDestinationView> submitDestination(
            @AuthenticationPrincipal UserPrincipal owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShopPayoutDestinationCommand command,
            HttpServletRequest request) {
        requirePayoutEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(payouts.submitDestination(owner, idempotencyKey, command, context(request, owner)));
    }

    @GetMapping("/payouts")
    public ResponseEntity<List<ShopOwnerPayoutView>> ownerPayouts(@AuthenticationPrincipal UserPrincipal owner) {
        requirePayoutEnabled();
        return privateResponse(payouts.ownerPayouts(owner));
    }

    @PostMapping("/payouts/{periodId}")
    public ResponseEntity<ShopOwnerPayoutView> requestPayout(@AuthenticationPrincipal UserPrincipal owner,
            @PathVariable UUID periodId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShopPayoutRequestCommand command, HttpServletRequest request) {
        requirePayoutEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(payouts.requestPayout(owner, periodId, idempotencyKey, command, context(request, owner)));
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
                .body(new ConflictResponse("PAYOUT_CONFLICT", "Yêu cầu đã được ghi nhận hoặc bị trùng dữ liệu."));
    }

    public record ConflictResponse(String code, String message) { }
}
