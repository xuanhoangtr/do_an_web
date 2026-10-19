package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.impl.VendorOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/vendor/orders")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorOrderWebController {
    private final VendorOrderService orders;
    private final FulfillmentService commands;

    public VendorOrderWebController(VendorOrderService orders, FulfillmentService commands) {
        this.orders = orders;
        this.commands = commands;
    }

    @GetMapping({"", "/"})
    public String list(Authentication authentication, @RequestParam(defaultValue = "0") int page,
                       HttpServletResponse response, Model model) {
        noStore(response);
        model.addAttribute("orders", orders.list(actor(authentication), page));
        return "vendor/orders/index";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Authentication authentication, HttpServletResponse response, Model model) {
        noStore(response);
        model.addAttribute("order", orders.detail(actor(authentication), id));
        return "vendor/orders/detail";
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    @ResponseBody
    public String exportPage(Authentication authentication, @RequestParam(defaultValue = "0") int page,
                             HttpServletResponse response) {
        noStore(response);
        var rows = orders.list(actor(authentication), page);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=vendor-orders.csv");
        StringBuilder csv = new StringBuilder("Order number,Status,Payment status,Payment method,Created at\r\n");
        for (var row : rows) {
            csv.append(cell(row.getOrderNumber())).append(',').append(cell(row.getStatus())).append(',')
                    .append(cell(row.getPaymentStatus())).append(',').append(cell(row.getPaymentMethod())).append(',')
                    .append(cell(row.getCreatedAt())).append("\r\n");
        }
        return csv.toString();
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.matches("^[\\s]*[=+@-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    @PostMapping("/{id}/accept-cod")
    public String acceptCod(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                            HttpServletResponse response) {
        noStore(response);
        commands.acceptCodAsVendor(actor(authentication), id, context(request));
        return "redirect:/vendor/orders/" + id;
    }

    @PostMapping("/{id}/start-preparation")
    public String startPreparation(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                                   HttpServletResponse response) {
        noStore(response);
        commands.startPreparationAsVendor(actor(authentication), id, context(request));
        return "redirect:/vendor/orders/" + id;
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập cửa hàng.");
    }

    private static RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"), null, "VENDOR");
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
