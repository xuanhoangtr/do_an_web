package com.utewedding.app.controller;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.finance.ShopCommissionPolicyCommand;
import com.utewedding.app.service.impl.ShopFinancialService;
import com.utewedding.app.service.web.FormToken;
import com.utewedding.app.repository.ShopCommissionPolicyRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin-only versioned commission policy management. */
@Controller
@RequestMapping("/admin/finance/commission")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShopCommissionPolicyWebController {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ShopCommissionPolicyRepository policies;
    private final ShopFinancialService finance;
    private final FormToken tokens;
    private final WebConfirm confirm;

    public AdminShopCommissionPolicyWebController(ShopCommissionPolicyRepository policies,
                                                   ShopFinancialService finance,
                                                   FormToken tokens, WebConfirm confirm) {
        this.policies = policies;
        this.finance = finance;
        this.tokens = tokens;
        this.confirm = confirm;
    }

    @GetMapping
    public String index(HttpServletRequest request, Model model) {
        model.addAttribute("policies", policies.findAll(Sort.by(Sort.Direction.DESC, "policyVersion")));
        model.addAttribute("token", tokens.issue(request, "finance:commission:create"));
        LocalDateTime next = LocalDateTime.now(VIETNAM).plusMinutes(5);
        var latest = policies.findTopByOrderByEffectiveAtDesc().orElse(null);
        if (latest != null) {
            LocalDateTime afterLatest = latest.getEffectiveAt().atZone(VIETNAM).toLocalDateTime().plusMinutes(1);
            if (afterLatest.isAfter(next)) next = afterLatest;
        }
        model.addAttribute("defaultEffectiveAt", next.withSecond(0).withNano(0).toString());
        return "operations/commission-policy";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal UserPrincipal user,
                         @RequestParam BigDecimal ratePercent,
                         @RequestParam String effectiveAt,
                         @RequestParam String token,
                         @RequestParam(defaultValue = "false") boolean confirmed,
                         HttpServletRequest request, Model model, RedirectAttributes flash) {
        try {
            int rateBps = ratePercent.movePointRight(2).intValueExact();
            LocalDateTime localEffectiveAt = LocalDateTime.parse(effectiveAt);
            var effectiveInstant = localEffectiveAt.atZone(VIETNAM).toInstant();
            Map<String, String> fields = Map.of("ratePercent", ratePercent.toPlainString(),
                    "effectiveAt", effectiveAt);
            if (!confirm.ready(request, model, "finance:commission:create", token, confirmed,
                    fields, "Xác nhận lịch sử dụng hoa hồng mới",
                    ratePercent.stripTrailingZeros().toPlainString() + "% từ " + effectiveAt)) {
                return "operations/confirm";
            }
            finance.setGlobalCommissionRate(user.getId(),
                    new ShopCommissionPolicyCommand(rateBps, effectiveInstant),
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-ID"), user.getRole().name()));
            flash.addFlashAttribute("successMessage", "Đã tạo phiên bản chính sách hoa hồng mới.");
        } catch (RuntimeException error) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(error));
        }
        return "redirect:/admin/finance/commission";
    }
}
