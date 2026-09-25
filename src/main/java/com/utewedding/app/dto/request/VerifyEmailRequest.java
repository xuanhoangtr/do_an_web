package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Mã xác thực phải gồm 6 chữ số.") String otp) {
}
