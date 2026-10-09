package com.utewedding.app.controller;

import com.utewedding.app.service.ProductFavoriteService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import com.utewedding.app.service.web.CurrentUserContext;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/products")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ProductFavoriteWebController {
    private final ProductFavoriteService favoriteService;
    private final CurrentUserContext currentUser;

    public ProductFavoriteWebController(ProductFavoriteService favoriteService,
                                        CurrentUserContext currentUser) {
        this.favoriteService = favoriteService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{productId}/favorite")
    @ResponseBody
    public ProductFavoriteController.FavoriteResponse get(@PathVariable UUID productId,
                                                           Authentication authentication,
                                                           HttpServletRequest request) {
        return response(favoriteService.get(currentUser.requireUserId(authentication), productId, context(request)));
    }

    @PostMapping("/{productId}/favorite")
    @ResponseBody
    public ProductFavoriteController.FavoriteResponse add(@PathVariable UUID productId,
                                                           Authentication authentication,
                                                           HttpServletRequest request) {
        UUID userId = currentUser.requireUserId(authentication);
        return response(favoriteService.add(userId, productId, context(request)));
    }

    @DeleteMapping("/{productId}/favorite")
    @ResponseBody
    public ProductFavoriteController.FavoriteResponse remove(@PathVariable UUID productId,
                                                              Authentication authentication,
                                                              HttpServletRequest request) {
        UUID userId = currentUser.requireUserId(authentication);
        return response(favoriteService.remove(userId, productId, context(request)));
    }

    private ProductFavoriteController.FavoriteResponse response(ProductFavoriteService.FavoriteState state) {
        return new ProductFavoriteController.FavoriteResponse(state.productId(), state.favorited(), state.count());
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
