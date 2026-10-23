package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Editable profile fields exposed by the admin user detail page. */
public class AdminUserProfileForm {

    @NotBlank(message = "Họ tên không được để trống.")
    @Size(max = 120, message = "Họ tên tối đa 120 ký tự.")
    private String displayName;

    @NotBlank(message = "Email không được để trống.")
    @Email(message = "Email không hợp lệ.")
    @Size(max = 320, message = "Email tối đa 320 ký tự.")
    private String email;

    public AdminUserProfileForm() { }

    public AdminUserProfileForm(String displayName, String email) {
        this.displayName = displayName;
        this.email = email;
    }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
