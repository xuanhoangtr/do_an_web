package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @jakarta.validation.constraints.Pattern(regexp = "^(?:[a-zA-Z0-9._-]{1,64})?$",
                message = "Tên đăng nhập chỉ gồm chữ cái, số, dấu chấm, gạch dưới hoặc gạch ngang.")
        String username,
        @NotBlank
        @Size(min = 10, max = 128)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Mật khẩu phải có ít nhất 10 ký tự, gồm chữ thường, chữ hoa và số.")
        String password,
        @NotBlank @Size(max = 120) String displayName) {

    public RegisterRequest(String email, String password, String displayName) {
        this(email, null, password, displayName);
    }
}
