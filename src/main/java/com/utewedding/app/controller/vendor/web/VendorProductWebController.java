package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.dto.request.VendorProductForm;
import com.utewedding.app.dto.response.VendorProductView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ManagedMediaService;
import com.utewedding.app.service.impl.VendorProductService;
import com.utewedding.app.repository.CatalogCategoryRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Server-rendered vendor product management. Shop identity is resolved from the authenticated owner. */
@Controller
@RequestMapping("/vendor/products")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorProductWebController {
    private final VendorProductService products;
    private final CatalogCategoryRepository categories;
    @Autowired(required = false)
    private com.utewedding.app.integration.CloudinaryService cloudinary;
    @Autowired(required = false)
    private ManagedMediaService media;

    public VendorProductWebController(VendorProductService products, CatalogCategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @GetMapping({"", "/"})
    public String list(Authentication authentication, HttpServletResponse response,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        noStore(response);
        model.addAttribute("products", products.list(actor(authentication), page));
        return "vendor/products/index";
    }

    @GetMapping("/new")
    public String createForm(Authentication authentication, HttpServletResponse response, Model model) {
        noStore(response);
        actor(authentication);
        model.addAttribute("productForm", new VendorProductForm());
        model.addAttribute("product", null);
        addCategories(model);
        return "vendor/products/form";
    }

    @GetMapping("/{productId}/edit")
    public String editForm(@PathVariable UUID productId, Authentication authentication,
                           HttpServletResponse response, Model model) {
        noStore(response);
        VendorProductView view = products.get(actor(authentication), productId);
        model.addAttribute("productForm", form(view));
        model.addAttribute("product", view);
        addCategories(model);
        return "vendor/products/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("productForm") VendorProductForm form,
                         BindingResult binding, @RequestParam(name = "image", required = false) MultipartFile image,
                         Authentication authentication, HttpServletResponse response, Model model,
                         RedirectAttributes flash) {
        noStore(response);
        UserPrincipal principal = actor(authentication);
        if (binding.hasErrors()) return renderForm(model, form, null);
        UUID uploadedMediaId = null;
        UUID originalMediaId = form.getManagedMediaId();
        try {
            products.authorizeMutation(principal);
            uploadedMediaId = upload(image, form, principal);
            products.create(principal, form);
            flash.addFlashAttribute("successMessage", "Đã gửi sản phẩm để quản trị viên kiểm duyệt.");
            return "redirect:/vendor/products";
        } catch (AccessDeniedException exception) {
            abort(uploadedMediaId);
            form.setManagedMediaId(originalMediaId);
            throw exception;
        } catch (RuntimeException exception) {
            abort(uploadedMediaId);
            form.setManagedMediaId(originalMediaId);
            model.addAttribute("formError", userMessage(exception));
            return renderForm(model, form, null);
        }
    }

    @PostMapping("/{productId}")
    public String update(@PathVariable UUID productId,
                         @Valid @ModelAttribute("productForm") VendorProductForm form,
                         BindingResult binding, @RequestParam(name = "image", required = false) MultipartFile image,
                         Authentication authentication, HttpServletResponse response, Model model,
                         RedirectAttributes flash) {
        noStore(response);
        UserPrincipal principal = actor(authentication);
        if (binding.hasErrors()) return renderForm(model, form, safeGet(principal, productId));
        UUID uploadedMediaId = null;
        UUID originalMediaId = form.getManagedMediaId();
        try {
            products.authorizeMutation(principal);
            uploadedMediaId = upload(image, form, principal);
            VendorProductView saved = products.update(principal, productId, form);
            flash.addFlashAttribute("successMessage", saved.moderationStatus()
                    == com.utewedding.app.domain.ProductModerationStatus.BLOCKED
                    ? "Đã lưu nội dung. Sản phẩm vẫn bị chặn và cần quản trị viên xem xét."
                    : "Đã lưu nội dung. Sản phẩm cần được quản trị viên kiểm duyệt lại trước khi mở bán.");
            return "redirect:/vendor/products";
        } catch (AccessDeniedException exception) {
            abort(uploadedMediaId);
            form.setManagedMediaId(originalMediaId);
            throw exception;
        } catch (RuntimeException exception) {
            abort(uploadedMediaId);
            form.setManagedMediaId(originalMediaId);
            model.addAttribute("formError", userMessage(exception));
            return renderForm(model, form, safeGet(principal, productId));
        }
    }

    @PostMapping("/{productId}/visibility")
    public String toggle(@PathVariable UUID productId, Authentication authentication,
                         HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        try {
            VendorProductView product = products.toggleVisibility(actor(authentication), productId);
            flash.addFlashAttribute("successMessage", product.sellerVisible()
                    ? "Sản phẩm được đặt ở chế độ hiển thị; sản phẩm chờ duyệt vẫn chưa bán được."
                    : "Đã ẩn sản phẩm khỏi cửa hàng.");
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/products";
    }

    @PostMapping("/{productId}/hide")
    public String hide(@PathVariable UUID productId, Authentication authentication,
                       HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        try {
            products.hide(actor(authentication), productId);
            flash.addFlashAttribute("successMessage", "Đã ẩn sản phẩm khỏi cửa hàng.");
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/products";
    }

    private String renderForm(Model model, VendorProductForm form, VendorProductView product) {
        model.addAttribute("productForm", form);
        model.addAttribute("product", product);
        addCategories(model);
        return "vendor/products/form";
    }

    private void addCategories(Model model) {
        model.addAttribute("productCategoryOptions", categories.findByActiveTrueOrderByDisplayOrderAscNameAsc());
    }

    private UUID upload(MultipartFile file, VendorProductForm form, UserPrincipal actor) {
        if (file == null || file.isEmpty()) return null;
        if (cloudinary == null || media == null) {
            throw new BusinessConflictException("Dịch vụ tải ảnh sản phẩm chưa sẵn sàng.");
        }
        UUID shopId = products.shopId(actor);
        var uploaded = cloudinary.uploadProductImage(file, actor.getId().toString());
        var tracked = media.trackPublicProductUpload(file, actor.getId(), shopId, uploaded);
        form.setManagedMediaId(tracked.getId());
        return tracked.getId();
    }

    private void abort(UUID mediaId) {
        if (mediaId != null && media != null) {
            try { media.abortPublicProductUpload(mediaId); }
            catch (RuntimeException ignored) { }
        }
    }

    private VendorProductView safeGet(UserPrincipal actor, UUID productId) {
        try { return products.get(actor, productId); }
        catch (RuntimeException ignored) { return null; }
    }

    private VendorProductForm form(VendorProductView view) {
        VendorProductForm form = new VendorProductForm();
        form.setCategoryId(view.categoryId());
        form.setName(view.name());
        form.setSku(view.sku());
        form.setShortDescription(view.shortDescription());
        form.setDescription(view.description());
        form.setCustomizationGuide(view.customizationGuide());
        form.setUnitPrice(view.unitPrice());
        form.setCompareAtPrice(view.compareAtPrice());
        form.setMinOrderQuantity(view.minOrderQuantity());
        form.setWeightGrams(view.weightGrams());
        form.setDimensions(view.dimensions());
        form.setMaterial(view.material());
        form.setDiscountPercent(view.discountPercent());
        form.setCustomizable(view.customizable());
        form.setManagedMediaId(view.managedMediaId());
        form.setVersion(view.version());
        return form;
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
        if (exception instanceof ResponseStatusException responseStatus && responseStatus.getReason() != null) {
            return responseStatus.getReason().substring(0, Math.min(280, responseStatus.getReason().length()));
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
