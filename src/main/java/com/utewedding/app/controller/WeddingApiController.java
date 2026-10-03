package com.utewedding.app.controller;

import com.utewedding.app.dto.request.CreateWeddingRequest;
import com.utewedding.app.dto.request.ModerateWishRequest;
import com.utewedding.app.dto.request.UpdateTimelineRequest;
import com.utewedding.app.dto.request.UpdateWeddingRequest;
import com.utewedding.app.dto.response.TimelineItemResponse;
import com.utewedding.app.dto.response.WeddingResponse;
import com.utewedding.app.dto.response.WishResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.WeddingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/weddings")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class WeddingApiController {

    private final WeddingService weddingService;

    public WeddingApiController(WeddingService weddingService) {
        this.weddingService = weddingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WeddingResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                  @Valid @RequestBody CreateWeddingRequest request,
                                  HttpServletRequest httpRequest) {
        return weddingService.create(principal.getId(), request, context(httpRequest));
    }

    @GetMapping("/{weddingId}")
    public WeddingResponse get(@AuthenticationPrincipal UserPrincipal principal,
                               @PathVariable UUID weddingId) {
        return weddingService.get(weddingId, principal.getId());
    }

    @PutMapping("/{weddingId}")
    public WeddingResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                  @PathVariable UUID weddingId,
                                  @Valid @RequestBody UpdateWeddingRequest request,
                                  HttpServletRequest httpRequest) {
        return weddingService.update(weddingId, principal.getId(), request, context(httpRequest));
    }

    @GetMapping("/{weddingId}/timeline")
    public List<TimelineItemResponse> getTimeline(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable UUID weddingId) {
        return weddingService.getTimeline(weddingId, principal.getId());
    }

    @PutMapping("/{weddingId}/timeline")
    public List<TimelineItemResponse> updateTimeline(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable UUID weddingId,
                                                     @Valid @RequestBody UpdateTimelineRequest request,
                                                     HttpServletRequest httpRequest) {
        return weddingService.updateTimeline(weddingId, principal.getId(), request, context(httpRequest));
    }

    @PatchMapping("/{weddingId}/wishes/{wishId}")
    public WishResponse moderateWish(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable UUID weddingId,
                                     @PathVariable UUID wishId,
                                     @Valid @RequestBody ModerateWishRequest request,
                                     HttpServletRequest httpRequest) {
        return weddingService.moderateWish(weddingId, wishId, principal.getId(), request,
                context(httpRequest));
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
