package com.utewedding.app.controller;

import com.utewedding.app.dto.request.RegisterRequest;
import com.utewedding.app.exception.DuplicateRegistrationException;
import com.utewedding.app.exception.InvalidOtpException;
import com.utewedding.app.service.AuthService;
import com.utewedding.app.dto.request.ResendVerificationRequest;
import com.utewedding.app.dto.request.ResetPasswordRequest;
import com.utewedding.app.dto.request.VerifyEmailRequest;
import com.utewedding.app.service.EmailVerificationService;
import com.utewedding.app.service.PasswordResetService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.WebViewModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Objects;

@Controller
public class AuthWebController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;

    public AuthWebController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthWebController(AuthService authService, PasswordResetService passwordResetService,
                             org.springframework.beans.factory.ObjectProvider<EmailVerificationService> emailVerificationService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService.getIfAvailable();
    }

    @GetMapping("/login")
    public String login(@RequestParam(defaultValue = "login") String tab, Model model,
                        org.springframework.security.core.Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            return "redirect:" + com.utewedding.app.config.SecurityConfig.loginSuccessUrl(authentication);
        }
        prepareLoginModel(model, normalizeTab(tab));
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("registrationForm", new WebViewModels.RegistrationForm());
        model.addAttribute("activeTab", "register");
        return "auth/register";
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "redirect:/login?tab=forgot";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("registrationForm") WebViewModels.RegistrationForm form,
                           BindingResult bindingResult, RedirectAttributes redirectAttributes,
                           HttpServletRequest request) {
        if (!Objects.equals(form.getPassword(), form.getConfirmPassword())) {
            bindingResult.rejectValue("confirmPassword", "password.mismatch", "Mật khẩu xác nhận không khớp.");
        }
        if (bindingResult.hasErrors()) return "auth/register";
        try {
            authService.register(new RegisterRequest(form.getEmail(), form.getUsername(),
                    form.getPassword(), form.getDisplayName()),
                    context(request));
        } catch (DuplicateRegistrationException duplicate) {
            if (duplicate instanceof com.utewedding.app.exception.DuplicateUsernameException) {
                bindingResult.rejectValue("username", "registration.username.duplicate",
                        "Tên đăng nhập này đã được sử dụng.");
            } else {
                bindingResult.rejectValue("email", "registration.duplicate",
                        "Email này đã được đăng ký. Vui lòng đăng nhập hoặc dùng chức năng quên mật khẩu.");
            }
            return "auth/register";
        } catch (RuntimeException exception) {
            bindingResult.reject("registration.failed", WebErrorMessages.forException(exception));
            return "auth/register";
        }
        redirectAttributes.addFlashAttribute("successMessage",
                "Đăng ký thành công. Nhập mã OTP 6 số đã gửi đến email để xác thực tài khoản.");
        redirectAttributes.addFlashAttribute("email", form.getEmail() == null ? "" : form.getEmail().trim());
        return "redirect:/verify-email";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(
            @Valid @ModelAttribute("forgotPasswordForm") WebViewModels.ForgotPasswordForm form,
            BindingResult bindingResult, Model model, HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        prepareLoginModel(model, "forgot");
        if (bindingResult.hasErrors()) return "auth/login";

        try {
            passwordResetService.request(form.getEmail(), request.getRemoteAddr());
        } catch (RuntimeException exception) {
            // Keep the public response identical if the reset queue/database is unavailable.
        }

        redirectAttributes.addFlashAttribute("successMessage", "Nếu email tồn tại, hướng dẫn sẽ được gửi sớm.");
        return "redirect:/login?tab=forgot";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token == null ? "" : token);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@Valid @ModelAttribute("resetPasswordForm") WebViewModels.ResetPasswordForm form,
                                BindingResult bindingResult,
                                HttpServletRequest request,
                                RedirectAttributes redirectAttributes, Model model) {
        model.addAttribute("token", form.getToken() == null ? "" : form.getToken());
        model.addAttribute("email", form.getEmail());
        if (bindingResult.hasErrors()) {
            model.addAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "auth/reset-password";
        }
        try {
            passwordResetService.reset(new ResetPasswordRequest(
                    blankToNull(form.getToken()), blankToNull(form.getEmail()),
                    blankToNull(form.getOtp()), form.getNewPassword()), context(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đặt lại mật khẩu thành công. Hãy đăng nhập bằng mật khẩu mới.");
            return "redirect:/login";
        } catch (RuntimeException exception) {
            model.addAttribute("errorMessage", WebErrorMessages.forException(exception));
            return "auth/reset-password";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(required = false) String token,
                              RedirectAttributes redirectAttributes) {
        if (token != null && !token.isBlank()) {
            redirectAttributes.addFlashAttribute("infoMessage",
                    "Vui lòng nhập mã OTP 6 số trong email để xác thực tài khoản.");
            return "redirect:/verify-email";
        }
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String verifyEmailCode(@RequestParam String email, @RequestParam String code,
                                  HttpServletRequest request, Model model,
                                  RedirectAttributes redirectAttributes) {
        try {
            emailVerificationService.verifyOtp(new VerifyEmailRequest(email, code), context(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Email đã được xác thực. Bạn có thể đăng nhập.");
            return "redirect:/login";
        } catch (InvalidOtpException exception) {
            model.addAttribute("errorMessage",
                    "Mã OTP chưa đúng. Email vẫn chưa được xác thực; vui lòng kiểm tra mã trong email và nhập lại.");
        } catch (RuntimeException exception) {
            model.addAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        model.addAttribute("email", email);
        return "auth/verify-email";
    }

    @PostMapping("/verify-email/resend")
    public String resendVerification(@RequestParam String email, HttpServletRequest request,
                                     RedirectAttributes redirectAttributes) {
        try {
            if (emailVerificationService != null) {
                emailVerificationService.resend(new ResendVerificationRequest(email), context(request));
            }
        } catch (RuntimeException ignored) {
            // Same response whether or not the address can receive another message.
        }
        redirectAttributes.addFlashAttribute("successMessage",
                "Nếu email cần xác thực, mã OTP mới sẽ được gửi sớm.");
        redirectAttributes.addFlashAttribute("email", email == null ? "" : email.trim());
        return "redirect:/verify-email";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void prepareLoginModel(Model model, String activeTab) {
        model.addAttribute("activeTab", activeTab);
        if (!model.containsAttribute("registrationForm")) {
            model.addAttribute("registrationForm", new WebViewModels.RegistrationForm());
        }
        if (!model.containsAttribute("forgotPasswordForm")) {
            model.addAttribute("forgotPasswordForm", new WebViewModels.ForgotPasswordForm());
        }
    }

    private String normalizeTab(String tab) {
        if ("register".equalsIgnoreCase(tab)) return "register";
        // Keep old reset links/bookmarks working without exposing a reset tab.
        if ("forgot".equalsIgnoreCase(tab) || "reset".equalsIgnoreCase(tab)) return "forgot";
        return "login";
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
