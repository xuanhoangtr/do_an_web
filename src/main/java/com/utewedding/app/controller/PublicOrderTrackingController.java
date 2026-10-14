package com.utewedding.app.controller;

import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.service.OrderService;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.servlet.http.HttpServletResponse;

/** Public entry point that accepts an order number without exposing order data. */
@Controller
public class PublicOrderTrackingController {

    private final OrderService orders;

    public PublicOrderTrackingController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/track")
    public String form(@RequestParam(required = false) String code, Model model) {
        model.addAttribute("code", code == null ? "" : code.trim());
        if (code == null || code.isBlank()) return "orders/track";

        String normalized = code.trim();
        UUID trackingCode = parseCode(normalized);
        if (trackingCode == null) {
            model.addAttribute("errorMessage", "Mã tra cứu không hợp lệ.");
            return "orders/track";
        }
        return "redirect:/orders/track/" + trackingCode;
    }

    @GetMapping("/orders/track/{code}")
    public String lookup(@PathVariable String code, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        UUID trackingCode = parseCode(code);
        if (trackingCode == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
        model.addAttribute("tracking", orders.getPublicTracking(trackingCode));
        model.addAttribute("code", trackingCode.toString());
        return "orders/track";
    }

    private UUID parseCode(String value) {
        if (value == null) return null;
        try {
            UUID code = UUID.fromString(value.trim());
            return code.toString().equalsIgnoreCase(value.trim()) ? code : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
