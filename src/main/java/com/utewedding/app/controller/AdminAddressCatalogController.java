package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.AdministrativeAddressCatalogStateEntity;
import com.utewedding.app.repository.AdministrativeAddressCatalogStateRepository;
import com.utewedding.app.repository.ProvinceRepository;
import com.utewedding.app.repository.WardRepository;
import com.utewedding.app.service.impl.GhnAddressCatalogPersistenceService;
import com.utewedding.app.service.impl.GhnAddressCatalogSyncService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/settings/address-catalog")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminAddressCatalogController {
    private final AdministrativeAddressCatalogStateRepository state;
    private final ProvinceRepository provinces;
    private final WardRepository wards;
    private final GhnAddressCatalogSyncService sync;
    private final GhnAddressCatalogPersistenceService persistence;

    public AdminAddressCatalogController(AdministrativeAddressCatalogStateRepository state,
            ProvinceRepository provinces, WardRepository wards, GhnAddressCatalogSyncService sync,
            GhnAddressCatalogPersistenceService persistence) {
        this.state = state;
        this.provinces = provinces;
        this.wards = wards;
        this.sync = sync;
        this.persistence = persistence;
    }

    @GetMapping
    public String show(Model model) {
        model.addAttribute("catalogState", state.findById((short) 1).orElse(null));
        model.addAttribute("activeGhnProvinces", provinces.countByCatalogSourceAndActiveTrue("GHN_STAGING"));
        model.addAttribute("activeGhnWards", wards.countByCatalogSourceAndActiveTrue("GHN_STAGING"));
        model.addAttribute("pickupAddressConfigured", persistence.pickupAddressConfigured());
        return "admin/settings-address-catalog";
    }

    @PostMapping("/sync")
    public String sync(RedirectAttributes flash) {
        try {
            var result = sync.sync();
            if (result == null) {
                flash.addFlashAttribute("errorMessage",
                        "Đang có lượt đồng bộ GHN khác chạy. Hãy đợi lượt đó kết thúc rồi thử lại.");
                return "redirect:/admin/settings/address-catalog";
            }
            flash.addFlashAttribute("successMessage", "Đã đồng bộ và kiểm tra " + result.provinceCount()
                    + " tỉnh/thành, " + result.wardCount()
                    + " phường/xã GHN Staging. Danh mục đang dùng chưa bị đổi.");
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", safe(exception.getMessage()));
        }
        return "redirect:/admin/settings/address-catalog";
    }

    @PostMapping("/activate")
    public String activate(RedirectAttributes flash) {
        try {
            persistence.activateGhn();
            flash.addFlashAttribute("successMessage",
                    "Checkout đã chuyển sang danh mục địa chỉ GHN Staging cho địa chỉ mới.");
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", safe(exception.getMessage()));
        }
        return "redirect:/admin/settings/address-catalog";
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "Không thể cập nhật danh mục địa chỉ.";
        String cleaned = value.replaceAll("(?i)(token|secret|authorization|password)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 500));
    }
}
