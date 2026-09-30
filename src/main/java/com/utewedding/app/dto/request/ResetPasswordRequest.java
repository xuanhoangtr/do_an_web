package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.utewedding.app.service.PasswordPolicy;

public record ResetPasswordRequest(
        @Size(max = 512) String token,
        @Email @Size(max = 320) String email,
        @Pattern(regexp = "^(?:\\d{6})?$", message = "OTP phải gồm 6 chữ số.") String otp,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String newPassword) {
    /** Backwards-compatible link reset constructor. */
    public ResetPasswordRequest(String token, String newPassword) {
        this(token, null, null, newPassword);
    }
}
