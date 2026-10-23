package com.utewedding.app.controller;

import com.utewedding.app.service.impl.AdminAttentionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/attention")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
public class AdminAttentionApiController {
    private final AdminAttentionService attention;

    public AdminAttentionApiController(AdminAttentionService attention) {
        this.attention = attention;
    }

    @GetMapping
    public AdminAttentionService.Snapshot snapshot() {
        return attention.snapshot();
    }
}
