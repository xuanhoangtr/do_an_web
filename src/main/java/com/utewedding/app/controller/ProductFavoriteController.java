package com.utewedding.app.controller;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.ProductFavoriteService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ProductFavoriteController {
    private final ProductFavoriteService favoriteService;

    public ProductFavoriteController(ProductFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/{productId}/favorite")
    public FavoriteResponse get(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID productId, HttpServletRequest request) {
        return response(favoriteService.get(principal.getId(), productId, context(request)));
    }

    @PostMapping("/{productId}/favorite")
    public FavoriteResponse add(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable UUID productId, HttpServletRequest request) {
        return response(favoriteService.add(principal.getId(), productId, context(request)));
    }

    @DeleteMapping("/{productId}/favorite")
    public FavoriteResponse remove(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable UUID productId, HttpServletRequest request) {
        return response(favoriteService.remove(principal.getId(), productId, context(request)));
    }

    private FavoriteResponse response(ProductFavoriteService.FavoriteState state) {
        return new FavoriteResponse(state.productId(), state.favorited(), state.count());
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }

    public record FavoriteResponse(UUID productId, boolean favorited, long count) { }
}
