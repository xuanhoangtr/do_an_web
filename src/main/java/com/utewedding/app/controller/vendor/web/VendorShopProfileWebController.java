package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.dto.request.VendorShopProfileForm;
import com.utewedding.app.dto.response.VendorShopProfileView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.CloudinaryPrivateMediaProxy;
import com.utewedding.app.service.impl.VendorShopProfileService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Shop profile and private application evidence endpoints; no shop or provider URL comes from the client. */
@Controller
@RequestMapping("/vendor/store")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorShopProfileWebController {
    private final VendorShopProfileService profiles;

    public VendorShopProfileWebController(VendorShopProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public String page(Authentication authentication, HttpServletResponse response, Model model) {
        noStore(response);
        addProfile(model, profiles.profile(actor(authentication)));
        return "vendor/store/index";
    }

    @PostMapping("/profile")
    public String updateProfile(@Valid @ModelAttribute("profileForm") VendorShopProfileForm form,
                                BindingResult binding, Authentication authentication,
                                HttpServletResponse response, Model model, RedirectAttributes flash) {
        noStore(response);
        UserPrincipal actor = actor(authentication);
        if (binding.hasErrors()) {
            model.addAttribute("profileView", profiles.profile(actor));
            model.addAttribute("profileForm", form);
            model.addAttribute("formError", "Vui lòng kiểm tra lại thông tin hồ sơ.");
            return "vendor/store/index";
        }
        try {
            profiles.updateProfile(actor, form);
            flash.addFlashAttribute("successMessage", "Đã lưu thông tin cửa hàng.");
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/store";
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadLogo(@RequestParam("file") MultipartFile file, Authentication authentication,
                             HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.uploadLogo(actor(authentication), file), flash,
                "Đã cập nhật logo cửa hàng.");
    }

    @PostMapping(value = "/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadBanner(@RequestParam("file") MultipartFile file, Authentication authentication,
                               HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.uploadBanner(actor(authentication), file), flash,
                "Đã cập nhật ảnh bìa cửa hàng.");
    }

    @PostMapping("/logo/remove")
    public String removeLogo(Authentication authentication, HttpServletResponse response,
                             RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.removeLogo(actor(authentication)), flash,
                "Đã gỡ logo cửa hàng.");
    }

    @PostMapping("/banner/remove")
    public String removeBanner(Authentication authentication, HttpServletResponse response,
                               RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.removeBanner(actor(authentication)), flash,
                "Đã gỡ ảnh bìa cửa hàng.");
    }

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadEvidence(@RequestParam("file") MultipartFile file, Authentication authentication,
                                 HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.uploadEvidence(actor(authentication), file), flash,
                "Đã lưu tài liệu riêng tư cho hồ sơ cửa hàng.");
    }

    @PostMapping("/evidence/{mediaId}/remove")
    public String removeEvidence(@PathVariable UUID mediaId, Authentication authentication,
                                 HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        return upload(() -> profiles.removeEvidence(actor(authentication), mediaId), flash,
                "Đã gỡ tài liệu khỏi danh sách.");
    }

    @GetMapping("/evidence/{mediaId}")
    public ResponseEntity<StreamingResponseBody> downloadEvidence(@PathVariable UUID mediaId,
                                                                  Authentication authentication,
                                                                  HttpServletResponse response) {
        noStore(response);
        VendorShopProfileService.EvidenceDownload download = profiles.downloadEvidence(actor(authentication), mediaId);
        CloudinaryPrivateMediaProxy.OpenedDownload stream = download.stream();
        StreamingResponseBody body = output -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                long written = 0;
                int count;
                while ((count = stream.inputStream().read(buffer)) != -1) {
                    written += count;
                    if (written > stream.maxBytes()) throw new IOException("Media exceeds the configured limit.");
                    output.write(buffer, 0, count);
                }
            }
        };
        ResponseEntity.BodyBuilder result = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(stream.contentType()));
        if (stream.contentLength() >= 0) result.contentLength(stream.contentLength());
        return result.body(body);
    }

    private String upload(Runnable operation, RedirectAttributes flash, String successMessage) {
        try {
            operation.run();
            flash.addFlashAttribute("successMessage", successMessage);
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/store";
    }

    private void addProfile(Model model, VendorShopProfileView view) {
        VendorShopProfileForm form = new VendorShopProfileForm();
        form.setShopName(view.shopName());
        form.setDescription(view.description());
        form.setContactEmail(view.contactEmail());
        form.setContactPhone(view.contactPhone());
        form.setVersion(view.version());
        model.addAttribute("profileView", view);
        model.addAttribute("profileForm", form);
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập khu vực cửa hàng.");
    }

    private static String userMessage(RuntimeException exception) {
        if (exception instanceof BusinessConflictException && exception.getMessage() != null) {
            return exception.getMessage().substring(0, Math.min(280, exception.getMessage().length()));
        }
        if (exception instanceof org.springframework.web.server.ResponseStatusException responseStatus
                && responseStatus.getReason() != null) {
            return responseStatus.getReason().substring(0, Math.min(280, responseStatus.getReason().length()));
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
