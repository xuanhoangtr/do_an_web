package com.utewedding.app.controller;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.exception.UnauthorizedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;

/** Stable, user-facing messages for server-rendered pages; backend details stay private. */
final class WebErrorMessages {

    private WebErrorMessages() {
    }

    static String forException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ResourceNotFoundException) {
                return "Không tìm thấy dữ liệu yêu cầu.";
            }
            if (current instanceof org.springframework.security.access.AccessDeniedException) return "Bạn không có quyền thực hiện thao tác này.";
            if (current instanceof com.utewedding.app.service.port.ShippingProviderException
                    && current.getMessage() != null) {
                return current.getMessage().substring(0, Math.min(400, current.getMessage().length()));
            }
            if (current instanceof BusinessConflictException && current.getMessage()!=null) return current.getMessage().substring(0,Math.min(280,current.getMessage().length()));
            if (current instanceof DataIntegrityViolationException) {
                return "Thao tác không thể thực hiện với trạng thái hiện tại.";
            }
            if (current instanceof UnauthorizedException || current instanceof AuthenticationException) {
                return "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.";
            }
            if (current instanceof ConstraintViolationException || current instanceof IllegalArgumentException) {
                return "Dữ liệu gửi lên chưa hợp lệ.";
            }
            current = current.getCause();
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }
}
