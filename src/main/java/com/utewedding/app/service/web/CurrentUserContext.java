package com.utewedding.app.service.web;

import com.utewedding.app.security.UserPrincipal;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserContext {
    public UUID requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Yêu cầu đăng nhập.");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal user) return user.getId();
        throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                "Không xác định được người dùng hiện tại.");
    }
}
