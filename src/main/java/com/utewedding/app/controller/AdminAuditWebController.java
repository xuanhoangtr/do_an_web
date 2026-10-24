package com.utewedding.app.controller;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.service.web.AdminAuditLogService;
import com.utewedding.app.service.web.AuditLogFilter;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Controller
public class AdminAuditWebController {
    private final AdminAuditLogService auditLogService;

    public AdminAuditWebController(AdminAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/admin/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String list(@ModelAttribute("filter") AuditLogFilter filter,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        model.addAttribute("actions", auditLogService.availableActions().stream()
                .map(action -> new AuditActionOption(action, auditLogService.actionLabel(action))).toList());
        try {
            model.addAttribute("auditLogs", auditLogService.search(filter, safePage));
        } catch (IllegalArgumentException exception) {
            model.addAttribute("auditLogs", Page.empty(PageRequest.of(safePage, AdminAuditLogService.PAGE_SIZE)));
            model.addAttribute("filterError", exception.getMessage());
        }
        return "admin/audit-logs";
    }

    @GetMapping("/admin/audit-logs/export")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> export(@ModelAttribute("filter") AuditLogFilter filter) {
        try {
            auditLogService.validate(filter);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
        StreamingResponseBody body = output -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write('\ufeff');
            auditLogService.writeCsv(filter, writer);
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("audit-logs.csv").build().toString())
                .body(body);
    }

    private record AuditActionOption(AuditAction action, String label) {
    }
}
