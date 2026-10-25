package com.utewedding.app.controller;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.AdminVendorFinanceDashboardService;
import com.utewedding.app.service.web.VendorDashboardRange;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.utewedding.app.util.CsvCellEncoder;

@Controller
@RequestMapping("/admin/finance/vendors")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminVendorFinanceDashboardWebController {
    private final AdminVendorFinanceDashboardService dashboard;

    public AdminVendorFinanceDashboardWebController(AdminVendorFinanceDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public String index(Authentication authentication,
                        @RequestParam(defaultValue = "last30") String range,
                        @RequestParam(required = false) LocalDate from,
                        @RequestParam(required = false) LocalDate to,
                        HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        VendorDashboardRange selected;
        try {
            selected = VendorDashboardRange.resolve(range, from, to,
                    LocalDate.now(VendorDashboardRange.ZONE));
        } catch (IllegalArgumentException invalidRange) {
            model.addAttribute("rangeError", invalidRange.getMessage());
            selected = VendorDashboardRange.resolve("last30", null, null,
                    LocalDate.now(VendorDashboardRange.ZONE));
        }
        model.addAttribute("financeDashboard", dashboard.read(actor(authentication), selected));
        return "admin/vendor-finance";
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(Authentication authentication,
                                         @RequestParam(defaultValue = "last30") String range,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        VendorDashboardRange selected;
        try {
            selected = VendorDashboardRange.resolve(range, from, to, LocalDate.now(VendorDashboardRange.ZONE));
        } catch (IllegalArgumentException invalidRange) {
            selected = VendorDashboardRange.resolve("last30", null, null,
                    LocalDate.now(VendorDashboardRange.ZONE));
        }
        var view = dashboard.read(actor(authentication), selected);
        var summary = view.summary();
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(CsvCellEncoder.row("Loại", "Khoản mục", "Từ ngày", "Đến ngày", "Giá trị")).append("\r\n");
        csv.append(CsvCellEncoder.row("Thông tin", "Khoảng thời gian", view.range().from().toString(),
                view.range().to().toString(), "")).append("\r\n");
        append(csv, "Tổng hợp", "Số shop Vendor", summary.shopCount());
        append(csv, "Số dư hiện tại", "Khả dụng", summary.available());
        append(csv, "Số dư hiện tại", "Chờ đối soát / chi", summary.pending());
        append(csv, "Số dư hiện tại", "Đang bị giữ", summary.held());
        append(csv, "Số dư hiện tại", "Đã chuyển khoản", summary.paid());
        append(csv, "Số dư hiện tại", "Công nợ thu hồi", summary.recovery());
        append(csv, "Trong kỳ", "Ghi có từ bán hàng", summary.sales());
        append(csv, "Trong kỳ", "Ghi nợ hoàn tiền", summary.refunds());
        append(csv, "Trong kỳ", "Hoa hồng theo snapshot", summary.commission());
        append(csv, "Trong kỳ", "Đã chuyển khoản", summary.paidInRange());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"vendor-finance-" + view.range().from() + "-"
                                + view.range().to() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder csv, String kind, String label, long value) {
        csv.append(CsvCellEncoder.row(kind, label, "", "", Long.toString(value))).append("\r\n");
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập tài chính nhà bán.");
    }
}
