package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChangePasswordForm {
    @NotBlank @Size(max = 128)
    private String currentPassword;
    @NotBlank @Size(min = 10, max = 128)
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Mật khẩu phải có ít nhất 10 ký tự, gồm chữ thường, chữ hoa và số.")
    private String newPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String value) { currentPassword = value; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String value) { newPassword = value; }
    public ChangePasswordRequest toRequest() {
        return new ChangePasswordRequest(currentPassword, newPassword);
    }
}
