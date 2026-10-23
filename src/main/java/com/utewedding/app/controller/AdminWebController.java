package com.utewedding.app.controller;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.dto.request.AdminUserProfileForm;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.CatalogCategoryRepository;
import com.utewedding.app.repository.ProductReviewRepository;
import com.utewedding.app.repository.AuthSessionRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.CheckoutGroupRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.AdminProductForm;
import com.utewedding.app.dto.response.AdminProductResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.AdminUserService;
import com.utewedding.app.service.AdminProductService;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.AdminUserFilter;
import com.utewedding.app.util.CsvCellEncoder;
import com.utewedding.app.service.web.WebAdminService;
import com.utewedding.app.service.web.WebViewModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.BindingResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Controller
public class AdminWebController {

    private static final ZoneId DASHBOARD_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WebAdminService adminService;
    private final AdminProductService productService;
    private final AdminUserService adminUserService;
    private final OrderService orderService;
    @Autowired(required = false)
    private FulfillmentService fulfillmentService;
    @Autowired(required = false)
    private OrderFulfillmentRepository fulfillmentRepository;
    @Autowired(required = false)
    private ProductReviewRepository reviewRepository;
    @Autowired(required = false)
    private ProductRepository productRepository;
    @Autowired(required = false)
    private PaymentRepository paymentRepository;
    @Autowired(required = false)
    private com.utewedding.app.service.impl.AdminShippingCostService shippingCosts;
    @Autowired(required = false)
    private CatalogCategoryRepository categoryRepository;
    @Autowired(required = false)
    private com.utewedding.app.integration.CloudinaryService cloudinaryService;
    @Autowired(required = false)
    private com.utewedding.app.service.impl.ManagedMediaService managedMediaService;
    @Autowired(required = false)
    private com.utewedding.app.service.impl.AdminAttentionService adminAttentionService;
    @Autowired(required = false)
    private AuthSessionRepository authSessionRepository;
    @Autowired(required = false)
    private OrderRepository orderRepository;
    @Autowired(required = false)
    private CheckoutGroupRepository checkoutGroupRepository;
    @Autowired(required = false)
    private com.utewedding.app.repository.CodReconciliationRepository codReconciliationRepository;
    @Autowired(required = false)
    private com.utewedding.app.repository.OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    @Autowired(required = false)
    private com.utewedding.app.service.impl.ShippingCarrierSnapshotReader shippingCarrierSnapshotReader;
    @Autowired(required = false)
    private com.utewedding.app.repository.FulfillmentShipmentRepository fulfillmentShipmentRepository;
    @Autowired(required = false)
    private com.utewedding.app.repository.ShippingProviderOperationRepository shippingOperationRepository;
    @Autowired(required = false)
    private com.utewedding.app.service.web.FormToken formTokens;
    @Autowired(required = false)
    private OrderItemRepository orderItemRepository;
    @Autowired(required = false)
    private WeddingRepository weddingRepository;
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired(required = false)
    private com.utewedding.app.security.WebSessionMetadataService webSessionMetadata;
    @Autowired(required = false)
    private com.utewedding.app.security.WebSessionRevocationService webSessionRevocationService;
    public AdminWebController(WebAdminService adminService, AdminProductService productService,
                              AdminUserService adminUserService, OrderService orderService) {
        this.adminService = adminService;
        this.productService = productService;
        this.adminUserService = adminUserService;
        this.orderService = orderService;
    }

    @GetMapping({"/admin", "/admin/dashboard"})
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String dashboard(@RequestParam(defaultValue = "last7") String range,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            Model model) {
        var stats = adminService.dashboardSnapshotStats();
        model.addAttribute("stats", stats);
        model.addAttribute("productCount", stats.getOrDefault("products", 0L));
        model.addAttribute("userCount", stats.getOrDefault("users", 0L));
        model.addAttribute("recentOrders", adminService.orders(0, 5).getContent());
        var attention = adminAttentionService == null
                ? new com.utewedding.app.service.impl.AdminAttentionService.Snapshot(0, 0, 0, 0, 0,
                        java.time.Instant.now())
                : adminAttentionService.snapshot();
        model.addAttribute("adminAttention", attention);
        model.addAttribute("attentionSnapshot", attention);
        model.addAttribute("pendingCount", attention.orders());
        model.addAttribute("opsPendingOrders", attention.orders());
        model.addAttribute("opsPendingTickets", attention.tickets());
        model.addAttribute("opsPendingReviews", attention.reviews());
        model.addAttribute("opsOpenReviewReports", attention.reviewReports());
        model.addAttribute("opsPendingReturns", attention.returns());
        if (fulfillmentRepository != null) {
            model.addAttribute("opsDeliveryFailed", fulfillmentRepository.countByStatus(FulfillmentStatus.DELIVERY_FAILED));
        }
        if (productRepository != null) {
            model.addAttribute("opsLowStock", productRepository.countByActiveTrueAndStockQuantityLessThanEqual(5));
        }
        model.addAttribute("featuredProducts", adminService.products("", "all", 0, 8).getContent());
        String selectedRange = List.of("today", "last7", "last30", "thisMonth", "custom").contains(range)
                ? range : "last7";
        model.addAttribute("selectedRange", selectedRange);
        model.addAttribute("rangeFrom", from == null ? "" : from.toString());
        model.addAttribute("rangeTo", to == null ? "" : to.toString());
        model.addAttribute("analyticsAvailable", false);
        try {
            var selected = com.utewedding.app.service.web.DashboardRange.resolve(
                    range, from, to, LocalDate.now(DASHBOARD_ZONE));
            var analytics = adminService.dashboardAnalytics(selected, DASHBOARD_ZONE);
            model.addAttribute("analytics", analytics);
            model.addAttribute("analyticsAvailable", true);
            model.addAttribute("rangeFrom", selected.from().toString());
            model.addAttribute("rangeTo", selected.to().toString());
            model.addAttribute("rangeLabel", selected.label());
            model.addAttribute("previousRangeLabel", analytics.previousRange().label());
            model.addAttribute("chartLabels", analytics.revenueByDay().stream()
                    .map(point -> point.date().format(java.time.format.DateTimeFormatter.ofPattern(
                            selected.dayCount() > 365 ? "dd/MM/yy" : "dd/MM")))
                    .toList());
            model.addAttribute("chartValues", analytics.revenueByDay().stream()
                    .map(com.utewedding.app.service.web.RevenuePoint::amount).toList());
            model.addAttribute("hasRevenueData", analytics.revenue().current() > 0);
            model.addAttribute("hasRangeData", analytics.revenue().current() > 0
                    || analytics.orders().current() > 0 || analytics.newUsers().current() > 0);
            model.addAttribute("lastUpdated", java.time.ZonedDateTime.now(DASHBOARD_ZONE)
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z")));
        } catch (IllegalArgumentException exception) {
            model.addAttribute("rangeError", exception.getMessage());
            if (!"custom".equals(range)) {
                model.addAttribute("selectedRange", "last7");
            }
        }
        return "admin/dashboard";
    }

    @GetMapping("/admin/dashboard/export")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportDashboard(@RequestParam(defaultValue = "last7") String range,
                                                  @RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to) {
        try {
            var selected = List.of("today", "last7", "last30", "thisMonth").contains(range)
                    && from != null && to != null
                    ? new com.utewedding.app.service.web.DashboardRange(range, from, to)
                    : com.utewedding.app.service.web.DashboardRange.resolve(
                            range, from, to, LocalDate.now(DASHBOARD_ZONE));
            if (selected.from().isAfter(selected.to())) {
                return ResponseEntity.badRequest().build();
            }
            var analytics = adminService.dashboardAnalytics(selected, DASHBOARD_ZONE);
            byte[] report = dashboardCsv(analytics);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("utewedding-dashboard-" + selected.from() + "-" + selected.to() + ".csv")
                            .build().toString())
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(report);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    private byte[] dashboardCsv(com.utewedding.app.service.web.DashboardAnalytics analytics) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, "Loại", "Chỉ số", "Ngày/khoảng thời gian", "Giá trị hiện tại",
                "Kỳ trước", "Chênh lệch", "Thay đổi (%)");
        appendCsvRow(csv, "Thông tin", "Kỳ hiện tại", analytics.range().label(), "", "", "", "");
        appendCsvRow(csv, "Thông tin", "Kỳ trước", analytics.previousRange().label(), "", "", "", "");
        appendMetricRow(csv, "Tổng", "Doanh thu (VND)", analytics.range().label(), analytics.revenue());
        appendMetricRow(csv, "Tổng", "Đơn hàng", analytics.range().label(), analytics.orders());
        appendMetricRow(csv, "Tổng", "Người dùng mới", analytics.range().label(), analytics.newUsers());
        for (var point : analytics.revenueByDay()) {
            appendCsvRow(csv, "Doanh thu theo ngày", "Doanh thu (VND)", point.date().toString(),
                    Long.toString(point.amount()), "", "", "");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendMetricRow(StringBuilder csv, String type, String name, String range,
                                 com.utewedding.app.service.web.DashboardMetric metric) {
        Double percent = metric.percentageChange();
        appendCsvRow(csv, type, name, range, Long.toString(metric.current()),
                Long.toString(metric.previous()), Long.toString(metric.delta()),
                percent == null ? "Không xác định (kỳ trước bằng 0)" : String.format(Locale.ROOT, "%.2f%%", percent));
    }

    private void appendCsvRow(StringBuilder csv, String... values) {
        csv.append(CsvCellEncoder.row(values))
                .append("\r\n");
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String users(@RequestParam(name = "q", defaultValue = "") String query,
                        @RequestParam(required = false) UserRole role,
                        @RequestParam(required = false) UserStatus status,
                        @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                        @RequestParam(defaultValue = "0") int page,
                        @AuthenticationPrincipal UserPrincipal principal,
                        Authentication authentication,
                        Model model) {
        AdminUserFilter filter = new AdminUserFilter(query, role, status, accounts);
        var users = adminService.users(filter, page, 50);
        if (page > 0 && (users.getTotalPages() == 0 || page >= users.getTotalPages())) {
            page = Math.max(0, users.getTotalPages() - 1);
            users = adminService.users(filter, page, 50);
        }
        model.addAttribute("users", users);
        model.addAttribute("filter", filter);
        model.addAttribute("roleOptions", UserRole.values());
        model.addAttribute("statusOptions", UserStatus.values());
        model.addAttribute("accountStateOptions", AdminUserFilter.AccountState.values());
        model.addAttribute("currentUserId", principal == null ? null : principal.getId());
        model.addAttribute("canChangeRoles", hasRole(authentication, UserRole.SUPER_ADMIN));
        return "admin/users";
    }

    @GetMapping("/admin/users/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String userDetail(@PathVariable UUID id,
                             @RequestParam(name = "q", defaultValue = "") String query,
                             @RequestParam(required = false) UserRole role,
                             @RequestParam(required = false) UserStatus status,
                             @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "0") int ordersPage,
                             @RequestParam(defaultValue = "0") int weddingsPage,
                             @AuthenticationPrincipal UserPrincipal principal,
                             Authentication authentication,
                             Model model) {
        populateUserDetailModel(id, query, role, status, accounts, page, ordersPage, weddingsPage,
                principal, authentication,
                model, null);
        return "admin/user-detail";
    }

    @PostMapping("/admin/users/{id}/web-sessions/{sessionId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String revokeWebSession(@PathVariable UUID id, @PathVariable String sessionId,
                                   RedirectAttributes redirectAttributes) {
        UserEntity user = adminUserService.get(id);
        boolean revoked = webSessionMetadata != null && webSessionMetadata.revoke(sessionId, user.getEmail());
        if (revoked && webSessionRevocationService != null) {
            webSessionRevocationService.revokeSessionAfterCommit(sessionId);
        }
        redirectAttributes.addFlashAttribute("successMessage", revoked
                ? "Đã thu hồi phiên trình duyệt." : "Phiên không còn hoạt động hoặc không thuộc người dùng này.");
        return "redirect:/admin/users/" + id + "#sessions";
    }

    @PostMapping("/admin/users/{id}/profile")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String updateUserProfile(@PathVariable UUID id,
                                    @Valid @ModelAttribute("profileForm") AdminUserProfileForm profileForm,
                                    BindingResult bindingResult,
                                    @AuthenticationPrincipal UserPrincipal actor,
                                    HttpServletRequest request,
                                    @RequestParam(name = "q", defaultValue = "") String query,
                                    @RequestParam(required = false) UserRole role,
                                    @RequestParam(required = false) UserStatus status,
                                    @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "0") int ordersPage,
                                    @RequestParam(defaultValue = "0") int weddingsPage,
                                    Authentication authentication,
                                    Model model,
                                    RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            populateUserDetailModel(id, query, role, status, accounts, page, ordersPage, weddingsPage,
                    actor, authentication,
                    model, profileForm);
            model.addAttribute("errorMessage", "Vui lòng kiểm tra lại thông tin hồ sơ.");
            return "admin/user-detail";
        }

        try {
            AdminUserService.ProfileUpdateResult result = adminUserService.updateProfile(actor.getId(), id,
                    profileForm.getDisplayName(), profileForm.getEmail(),
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")));
            redirectAttributes.addFlashAttribute("successMessage", result.emailChanged()
                    ? "Đã cập nhật hồ sơ. Email mới cần được xác minh trước khi người dùng đăng nhập lại."
                    : "Đã cập nhật hồ sơ người dùng.");
        } catch (BusinessConflictException exception) {
            populateUserDetailModel(id, query, role, status, accounts, page, ordersPage, weddingsPage,
                    actor, authentication,
                    model, profileForm);
            model.addAttribute("errorMessage", exception.getMessage());
            return "admin/user-detail";
        }

        redirectAttributes.addAttribute("q", query == null ? "" : query);
        if (role != null) redirectAttributes.addAttribute("role", role.name());
        if (status != null) redirectAttributes.addAttribute("status", status.name());
        redirectAttributes.addAttribute("accounts", accounts == null
                ? AdminUserFilter.AccountState.ACTIVE.name() : accounts.name());
        redirectAttributes.addAttribute("page", Math.max(0, page));
        redirectAttributes.addAttribute("ordersPage", Math.max(0, ordersPage));
        redirectAttributes.addAttribute("weddingsPage", Math.max(0, weddingsPage));
        return "redirect:/admin/users/" + id;
    }

    private void populateUserDetailModel(UUID id, String query, UserRole role, UserStatus status,
                                        AdminUserFilter.AccountState accounts, int page,
                                        int ordersPage, int weddingsPage,
                                        UserPrincipal principal, Authentication authentication,
                                        Model model, AdminUserProfileForm submittedForm) {
        UserEntity entity = adminUserService.get(id);
        model.addAttribute("user", new WebViewModels.AdminUserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole().name(),
                entity.getStatus().name(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getDeletedAt()));
        model.addAttribute("profileForm", submittedForm == null
                ? new AdminUserProfileForm(entity.getDisplayName(), entity.getEmail()) : submittedForm);
        model.addAttribute("currentUserId", principal == null ? null : principal.getId());
        model.addAttribute("canChangeRoles", hasRole(authentication, UserRole.SUPER_ADMIN));
        model.addAttribute("canEditProfile", canEditProfile(entity, authentication));
        model.addAttribute("backQuery", query);
        model.addAttribute("backRole", role);
        model.addAttribute("backStatus", status);
        model.addAttribute("backAccounts", accounts);
        model.addAttribute("backPage", Math.max(0, page));
        model.addAttribute("backOrdersPage", Math.max(0, ordersPage));
        model.addAttribute("backWeddingsPage", Math.max(0, weddingsPage));
        java.time.Instant now = java.time.Instant.now();
        model.addAttribute("webSessions", webSessions(entity.getEmail(), now));
        model.addAttribute("apiSessions", authSessionRepository == null ? List.of()
                : authSessionRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(id).stream()
                        .filter(session -> session.getExpiresAt() != null && session.getExpiresAt().isAfter(now))
                        .map(session -> new AdminApiSessionView(session.getId(), session.getCreatedAt(),
                                session.getLastUsedAt() == null ? session.getCreatedAt() : session.getLastUsedAt(),
                                session.getExpiresAt(), session.getUserAgent(), session.getIpAddress()))
                        .toList());
        int normalizedOrdersPage = Math.max(0, ordersPage);
        org.springframework.data.domain.Pageable ordersRequest = PageRequest.of(normalizedOrdersPage, 10,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        org.springframework.data.domain.Page<com.utewedding.app.domain.entity.OrderEntity> orderEntities =
                orderRepository == null ? org.springframework.data.domain.Page.empty(ordersRequest)
                        : orderRepository.findByUserIdOrderByCreatedAtDesc(id, ordersRequest);
        if (normalizedOrdersPage > 0 && orderEntities.getTotalPages() > 0
                && normalizedOrdersPage >= orderEntities.getTotalPages()) {
            normalizedOrdersPage = orderEntities.getTotalPages() - 1;
            ordersRequest = PageRequest.of(normalizedOrdersPage, 10,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
            if (orderRepository != null) orderEntities = orderRepository.findByUserIdOrderByCreatedAtDesc(id, ordersRequest);
        }
        org.springframework.data.domain.Page<AdminUserOrderSummary> userOrders = orderEntities.map(order -> {
                            List<com.utewedding.app.domain.entity.OrderItemEntity> items = orderItemRepository == null
                                    ? List.of() : orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
                            String products = items.isEmpty() ? "Không có thông tin sản phẩm"
                                    : items.getFirst().getProductName()
                                            + (items.size() > 1 ? " · + " + (items.size() - 1) + " sản phẩm" : "");
                            com.utewedding.app.domain.PaymentMethod method = null;
                            try {
                                method = order.getPaymentMethod() == null ? null
                                        : com.utewedding.app.domain.PaymentMethod.valueOf(order.getPaymentMethod());
                            } catch (IllegalArgumentException ignored) { }
                            return new AdminUserOrderSummary(order.getId(), order.getOrderNumber(), products,
                                    order.getTotalAmount(),
                                    com.utewedding.app.service.web.StatusLabels.orderStatusLabel(order.getStatus(), method),
                                    com.utewedding.app.service.web.StatusLabels.paymentStatusLabel(order.getPaymentStatus(), method),
                                    com.utewedding.app.service.web.StatusLabels.orderStatusBadgeClass(order.getStatus()),
                                    order.getCreatedAt());
                        });
        model.addAttribute("ordersPage", normalizedOrdersPage);
        model.addAttribute("backOrdersPage", normalizedOrdersPage);
        model.addAttribute("userOrders", userOrders);
        int normalizedWeddingsPage = Math.max(0, weddingsPage);
        org.springframework.data.domain.Pageable weddingsRequest = PageRequest.of(normalizedWeddingsPage, 10,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        org.springframework.data.domain.Page<com.utewedding.app.domain.entity.WeddingEntity> userWeddings =
                weddingRepository == null ? org.springframework.data.domain.Page.empty(weddingsRequest)
                        : weddingRepository.findByOwnerIdOrderByCreatedAtDesc(id, weddingsRequest);
        if (normalizedWeddingsPage > 0 && userWeddings.getTotalPages() > 0
                && normalizedWeddingsPage >= userWeddings.getTotalPages()) {
            normalizedWeddingsPage = userWeddings.getTotalPages() - 1;
            weddingsRequest = PageRequest.of(normalizedWeddingsPage, 10,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
            if (weddingRepository != null) userWeddings = weddingRepository.findByOwnerIdOrderByCreatedAtDesc(id, weddingsRequest);
        }
        model.addAttribute("userWeddings", userWeddings);
        model.addAttribute("weddingsPage", normalizedWeddingsPage);
        model.addAttribute("backWeddingsPage", normalizedWeddingsPage);
    }

    private List<AdminWebSessionView> webSessions(String email, java.time.Instant now) {
        if (jdbcTemplate == null || email == null || email.isBlank()) return List.of();
        try {
            return jdbcTemplate.query("""
                    select s.session_id, s.creation_time, s.last_access_time, s.expiry_time,
                           m.last_activity_at, m.user_agent, m.ip_address
                    from spring_session s
                    left join web_session_metadata m on m.session_id = s.session_id
                    where lower(s.principal_name) = lower(?) and s.expiry_time > ?
                    order by s.last_access_time desc
                    """, (rs, row) -> new AdminWebSessionView(rs.getString("session_id"),
                    java.time.Instant.ofEpochMilli(rs.getLong("creation_time")),
                    java.time.Instant.ofEpochMilli(rs.getLong("last_access_time")),
                    rs.getTimestamp("last_activity_at") == null
                            ? java.time.Instant.ofEpochMilli(rs.getLong("last_access_time"))
                            : rs.getTimestamp("last_activity_at").toInstant(),
                    java.time.Instant.ofEpochMilli(rs.getLong("expiry_time")), rs.getString("user_agent"),
                    rs.getString("ip_address")), email, now.toEpochMilli());
        } catch (org.springframework.dao.DataAccessException ignored) {
            try {
                return jdbcTemplate.query("""
                        select session_id, creation_time, last_access_time, expiry_time
                        from spring_session where lower(principal_name) = lower(?) and expiry_time > ?
                        order by last_access_time desc
                        """, (rs, row) -> new AdminWebSessionView(rs.getString("session_id"),
                        java.time.Instant.ofEpochMilli(rs.getLong("creation_time")),
                        java.time.Instant.ofEpochMilli(rs.getLong("last_access_time")),
                        java.time.Instant.ofEpochMilli(rs.getLong("last_access_time")),
                        java.time.Instant.ofEpochMilli(rs.getLong("expiry_time")), null, null), email, now.toEpochMilli());
            } catch (org.springframework.dao.DataAccessException unavailable) {
                return List.of();
            }
        }
    }

    public record AdminWebSessionView(String id, java.time.Instant loginAt, java.time.Instant lastAccessAt,
                                      java.time.Instant lastActivityAt, java.time.Instant expiresAt,
                                      String userAgent, String ipAddress) { }

    public record AdminApiSessionView(UUID id, java.time.Instant loginAt, java.time.Instant lastActivityAt,
                                      java.time.Instant expiresAt, String userAgent, String ipAddress) { }

    public record AdminUserOrderSummary(UUID id, String orderNumber, String products, long totalAmount,
                                        String orderStatus, String paymentStatus, String badgeClass,
                                        java.time.Instant createdAt) { }

    private boolean canEditProfile(UserEntity entity, Authentication authentication) {
        if (entity.getDeletedAt() != null || entity.getStatus() == UserStatus.DELETED) return false;
        if (hasRole(authentication, UserRole.SUPER_ADMIN)) return true;
        return entity.getRole() != UserRole.ADMIN && entity.getRole() != UserRole.SUPER_ADMIN;
    }

    @PostMapping("/admin/users/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String changeUserRole(@PathVariable UUID id,
                                 @RequestParam("newRole") UserRole newRole,
                                 @AuthenticationPrincipal UserPrincipal actor,
                                 HttpServletRequest request,
                                 @RequestParam(name = "q", defaultValue = "") String query,
                                 @RequestParam(required = false) UserRole role,
                                 @RequestParam(required = false) UserStatus status,
                                 @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                                 @RequestParam(defaultValue = "0") int page,
                                 RedirectAttributes redirectAttributes) {
        return updateAdminUser(id, actor, request,
                new AdminUserService.UserUpdateCommand(newRole, null, null, null),
                "Đã cập nhật vai trò người dùng.", query, role, status, accounts, page, redirectAttributes);
    }

    @PostMapping("/admin/users/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String changeUserStatus(@PathVariable UUID id,
                                   @RequestParam boolean enabled,
                                   @AuthenticationPrincipal UserPrincipal actor,
                                   HttpServletRequest request,
                                   @RequestParam(name = "q", defaultValue = "") String query,
                                   @RequestParam(required = false) UserRole role,
                                   @RequestParam(required = false) UserStatus status,
                                   @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                                   @RequestParam(defaultValue = "0") int page,
                                   RedirectAttributes redirectAttributes) {
        UserStatus nextStatus = enabled ? UserStatus.ACTIVE : UserStatus.SUSPENDED;
        String message = enabled
                ? "Đã mở khóa tài khoản. Các phiên đăng nhập trước đó đã bị thu hồi."
                : "Đã khóa tài khoản; các phiên đăng nhập đã được thu hồi.";
        return updateAdminUser(id, actor, request,
                new AdminUserService.UserUpdateCommand(null, null, nextStatus, null),
                message, query, role, status, accounts, page, redirectAttributes);
    }

    @PostMapping("/admin/users/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String softDeleteUser(@PathVariable UUID id,
                                 @AuthenticationPrincipal UserPrincipal actor,
                                 HttpServletRequest request,
                                 @RequestParam(name = "q", defaultValue = "") String query,
                                 @RequestParam(required = false) UserRole role,
                                 @RequestParam(required = false) UserStatus status,
                                 @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                                 @RequestParam(defaultValue = "0") int page,
                                 RedirectAttributes redirectAttributes) {
        return updateAdminUser(id, actor, request,
                new AdminUserService.UserUpdateCommand(null, null, null, true),
                "Tài khoản đã được xóa mềm và có thể khôi phục.",
                query, role, status, accounts, page, redirectAttributes);
    }

    @PostMapping("/admin/users/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String restoreUser(@PathVariable UUID id,
                              @AuthenticationPrincipal UserPrincipal actor,
                              HttpServletRequest request,
                              @RequestParam(name = "q", defaultValue = "") String query,
                              @RequestParam(required = false) UserRole role,
                              @RequestParam(required = false) UserStatus status,
                              @RequestParam(name = "accounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState accounts,
                              @RequestParam(defaultValue = "0") int page,
                              RedirectAttributes redirectAttributes) {
        return updateAdminUser(id, actor, request,
                new AdminUserService.UserUpdateCommand(null, null, null, false),
                "Đã khôi phục tài khoản.", query, role, status, accounts, page, redirectAttributes);
    }

    private String updateAdminUser(UUID id, UserPrincipal actor, HttpServletRequest request,
                                   AdminUserService.UserUpdateCommand command, String successMessage,
                                   String query, UserRole role, UserStatus status,
                                   AdminUserFilter.AccountState accounts, int page,
                                   RedirectAttributes redirectAttributes) {
        try {
            adminUserService.update(actor.getId(), id, command,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")));
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        redirectAttributes.addAttribute("q", query == null ? "" : query);
        if (role != null) redirectAttributes.addAttribute("role", role.name());
        if (status != null) redirectAttributes.addAttribute("status", status.name());
        redirectAttributes.addAttribute("accounts", accounts == null
                ? AdminUserFilter.AccountState.ACTIVE.name() : accounts.name());
        redirectAttributes.addAttribute("page", Math.max(0, page));
        return "redirect:/admin/users";
    }

    private boolean hasRole(Authentication authentication, UserRole role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role.name()));
    }

    @GetMapping("/admin/orders")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String orders(@RequestParam(defaultValue = "") String q,
                         @RequestParam(defaultValue = "") String buyer,
                         @RequestParam(required = false) OrderStatus status,
                         @RequestParam(required = false) PaymentStatus paymentStatus,
                         @RequestParam(required = false) FulfillmentStatus fulfillmentStatus,
                         @RequestParam(required = false) UUID shipperId,
                         @RequestParam(required = false) java.time.LocalDate from,
                         @RequestParam(required = false) java.time.LocalDate to,
                         @RequestParam(defaultValue = "false") boolean attention,
                         @RequestParam(defaultValue = "0") int page,
                         Authentication authentication, Model model) {
        boolean filtered = (q != null && !q.isBlank()) || (buyer != null && !buyer.isBlank())
                || status != null || paymentStatus != null || fulfillmentStatus != null
                || shipperId != null || from != null || to != null || attention;
        var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        model.addAttribute("filtered", filtered);
        PageRequest orderPage = attention
                ? PageRequest.of(Math.max(page, 0), 20, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
                : PageRequest.of(Math.max(page, 0), 20);
        org.springframework.data.domain.Page<?> orders = filtered
                ? orderService.searchForOperations(actorIdOrNull(authentication), q, buyer, status,
                paymentStatus, fulfillmentStatus, attention, shipperId,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                orderPage)
                : adminService.orders(Math.max(page, 0), 20);
        if (page > 0 && orders.getTotalPages() > 0 && page >= orders.getTotalPages()) {
            page = orders.getTotalPages() - 1;
            orders = filtered
                    ? orderService.searchForOperations(actorIdOrNull(authentication), q, buyer, status,
                    paymentStatus, fulfillmentStatus, attention, shipperId,
                    from == null ? null : from.atStartOfDay(zone).toInstant(),
                    to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                    attention ? PageRequest.of(page, 20, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
                            : PageRequest.of(page, 20))
                    : adminService.orders(page, 20);
        }
        model.addAttribute("orders", orders);
        java.util.List<UUID> pageOrderIds = orders.getContent().stream()
                .map(this::orderIdFromPageRow).filter(java.util.Objects::nonNull).toList();
        var checkoutGroupsByOrder = checkoutGroupsForRows(orders.getContent());
        model.addAttribute("checkoutGroupsByOrder", checkoutGroupsByOrder);
        model.addAttribute("hasGroupedVnpayOrder", checkoutGroupsByOrder.values().stream()
                .anyMatch(group -> "VNPAY".equals(group.getPaymentMethod())));
        model.addAttribute("shippingCarriers", shippingCarrierSnapshotReader == null ? java.util.Map.of()
                : shippingCarrierSnapshotReader.forOrders(pageOrderIds));
        model.addAttribute("attention", attention);
        model.addAttribute("attentionDetails", attention && adminAttentionService != null
                ? adminAttentionService.orderDetails((List<com.utewedding.app.dto.response.OrderResponse>) orders.getContent())
                : java.util.Map.of());
        model.addAttribute("q", q);
        model.addAttribute("buyer", buyer);
        model.addAttribute("shipperId", shipperId == null ? "" : shipperId.toString());
        model.addAttribute("from", from == null ? "" : from.toString());
        model.addAttribute("to", to == null ? "" : to.toString());
        model.addAttribute("status", status == null ? "" : status.name());
        model.addAttribute("paymentStatus", paymentStatus == null ? "" : paymentStatus.name());
        model.addAttribute("fulfillmentStatus", fulfillmentStatus == null ? "" : fulfillmentStatus.name());
        model.addAttribute("orderStatuses", OrderStatus.values());
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("fulfillmentStatuses", FulfillmentStatus.values());
        model.addAttribute("shippers", fulfillmentService == null ? List.of() : fulfillmentService.listShippers());
        return "admin/orders";
    }

    @GetMapping("/admin/orders/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String orderDetail(@PathVariable UUID id, Authentication authentication, Model model,
                              HttpServletRequest request,
                              @RequestParam(required = false) UUID returnUserId,
                              @RequestParam(defaultValue = "0") int returnOrdersPage,
                              @RequestParam(defaultValue = "0") int returnWeddingsPage,
                              @RequestParam(name = "returnQuery", defaultValue = "") String returnQuery,
                              @RequestParam(required = false, name = "returnRole") UserRole returnRole,
                              @RequestParam(required = false, name = "returnStatus") UserStatus returnStatus,
                              @RequestParam(name = "returnAccounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState returnAccounts,
                              @RequestParam(defaultValue = "0") int returnPage) {
        var order = orderService.getForOperations(actorIdOrNull(authentication), id);
        model.addAttribute("order", order);
        model.addAttribute("checkoutGroup", checkoutGroupForOrder(id));
        model.addAttribute("productDiscountTotal", order.items().stream()
                .mapToLong(com.utewedding.app.dto.response.OrderItemResponse::discountAmount).sum());
        model.addAttribute("returnUserId", returnUserId);
        model.addAttribute("returnOrdersPage", Math.max(0, returnOrdersPage));
        model.addAttribute("returnWeddingsPage", Math.max(0, returnWeddingsPage));
        model.addAttribute("returnQuery", returnQuery);
        model.addAttribute("returnRole", returnRole);
        model.addAttribute("returnStatus", returnStatus);
        model.addAttribute("returnAccounts", returnAccounts);
        model.addAttribute("returnPage", Math.max(0, returnPage));
        model.addAttribute("payments", paymentRepository == null ? java.util.List.of()
                : paymentRepository.findByOrderIdOrderByCreatedAtDesc(id));
        var codReconciliation = codReconciliationRepository == null ? null
                : codReconciliationRepository.findByOrderId(id).orElse(null);
        model.addAttribute("codReconciliation", codReconciliation);
        if (codReconciliation != null && formTokens != null
                && (codReconciliation.getStatus() == com.utewedding.app.domain.CodReconciliationStatus.AWAITING_AMOUNT
                || codReconciliation.getStatus() == com.utewedding.app.domain.CodReconciliationStatus.REVIEW_REQUIRED)) {
            model.addAttribute("codResolveToken", formTokens.issue(request,
                    "finance:cod:" + id + ":resolve"));
        }
        model.addAttribute("shippingProviderCode", orderShippingSnapshotRepository == null ? "UNKNOWN"
                : orderShippingSnapshotRepository.findByOrderId(id)
                .map(com.utewedding.app.domain.entity.OrderShippingSnapshotEntity::getProviderCode)
                .orElse("UNKNOWN"));
        model.addAttribute("shippingCarrierName", shippingCarrierSnapshotReader == null
                ? "Chưa xác định nhà vận chuyển" : shippingCarrierSnapshotReader.forOrder(id).name());
        java.util.Map<UUID, com.utewedding.app.domain.entity.FulfillmentShipmentEntity> shipmentsByFulfillment =
                new java.util.LinkedHashMap<>();
        java.util.Map<UUID, com.utewedding.app.domain.entity.ShippingProviderOperationEntity> shippingOperationsByFulfillment =
                new java.util.LinkedHashMap<>();
        if (fulfillmentShipmentRepository != null && order.fulfillments() != null) {
            for (var fulfillment : order.fulfillments()) {
                fulfillmentShipmentRepository.findFirstByFulfillmentIdOrderByCreatedAtDesc(fulfillment.id())
                        .ifPresent(shipment -> {
                            shipmentsByFulfillment.put(fulfillment.id(), shipment);
                            if (shippingOperationRepository != null) {
                                shippingOperationRepository.findFirstByShipmentIdOrderByCreatedAtDesc(shipment.getId())
                                        .ifPresent(operation -> shippingOperationsByFulfillment.put(
                                                fulfillment.id(), operation));
                            }
                        });
            }
        }
        model.addAttribute("shipmentsByFulfillment", shipmentsByFulfillment);
        model.addAttribute("shippingOperationsByFulfillment", shippingOperationsByFulfillment);
        var cost = shippingCosts == null ? null : shippingCosts.view(id);
        if (cost != null && "GHN".equals(cost.provider()) && cost.carrierFee() == null) {
            try { cost = shippingCosts.refresh(id); }
            catch (RuntimeException exception) {
                model.addAttribute("shippingCostError", WebErrorMessages.forException(exception));
            }
        }
        model.addAttribute("shippingCost", cost);
        return "admin/order-detail";
    }

    @GetMapping("/admin/orders/{id}/payment-status")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public ResponseEntity<AdminPaymentState> orderPaymentStatus(@PathVariable UUID id,
                                                                 Authentication authentication) {
        var order = orderService.getForOperations(actorIdOrNull(authentication), id);
        AdminPaymentState state = new AdminPaymentState(
                order.status() == null ? "UNKNOWN" : order.status().name(),
                order.paymentStatus() == null ? "UNKNOWN" : order.paymentStatus().name(),
                order.overallFulfillmentStatus() == null ? "UNKNOWN" : order.overallFulfillmentStatus().name());
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(state);
    }

    public record AdminPaymentState(String orderStatus, String paymentStatus, String fulfillmentStatus) { }

    @PostMapping("/admin/orders/{id}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String confirmCodOrder(@PathVariable UUID id, Authentication authentication,
                                  HttpServletRequest request, RedirectAttributes redirect) {
        try {
            fulfillmentService.confirmCodOrder(actorId(authentication), id,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")));
            redirect.addFlashAttribute("successMessage", "Đã xác nhận đơn COD; có thể bắt đầu chuẩn bị hàng.");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/admin/orders/{id}/shipping-cost")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String refreshShippingCost(@PathVariable UUID id, Authentication authentication,
                                      @RequestParam(required = false) UUID returnUserId,
                                      @RequestParam(name = "returnQuery", defaultValue = "") String returnQuery,
                                      @RequestParam(required = false, name = "returnRole") UserRole returnRole,
                                      @RequestParam(required = false, name = "returnStatus") UserStatus returnStatus,
                                      @RequestParam(name = "returnAccounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState returnAccounts,
                                      @RequestParam(defaultValue = "0") int returnPage,
                                      @RequestParam(defaultValue = "0") int returnOrdersPage,
                                      @RequestParam(defaultValue = "0") int returnWeddingsPage,
                                      RedirectAttributes redirect) {
        orderService.getForOperations(actorIdOrNull(authentication), id);
        try {
            shippingCosts.refresh(id);
            redirect.addFlashAttribute("successMessage", "Đã cập nhật cước GHN; phí thu khách không thay đổi.");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        addOrderProfileReturnAttributes(redirect, returnUserId, returnQuery, returnRole, returnStatus,
                returnAccounts, returnPage, returnOrdersPage, returnWeddingsPage);
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/admin/orders/{id}/refund")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String refund(@PathVariable UUID id, @RequestParam String providerTransactionId,
                         @RequestParam(defaultValue = "MANUAL_REFUND") String provider,
                         @RequestParam(required = false) String amount,
                         @RequestParam(required = false) UUID returnUserId,
                         @RequestParam(name = "returnQuery", defaultValue = "") String returnQuery,
                         @RequestParam(required = false, name = "returnRole") UserRole returnRole,
                         @RequestParam(required = false, name = "returnStatus") UserStatus returnStatus,
                         @RequestParam(name = "returnAccounts", defaultValue = "ACTIVE") AdminUserFilter.AccountState returnAccounts,
                         @RequestParam(defaultValue = "0") int returnPage,
                         @RequestParam(defaultValue = "0") int returnOrdersPage,
                         @RequestParam(defaultValue = "0") int returnWeddingsPage,
                         Authentication authentication, HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        try {
            Long refundAmount = null;
            if (amount != null && !amount.isBlank()) {
                refundAmount = Long.valueOf(amount.trim());
            }
            orderService.refund(actorId(authentication), id,
                    new PaymentRequest(providerTransactionId, provider, refundAmount),
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã xử lý hoàn tiền. VNPay và MoMo được gửi tới cổng thanh toán; hoàn thủ công chỉ dùng khi đơn không có giao dịch cổng.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        addOrderProfileReturnAttributes(redirectAttributes, returnUserId, returnQuery, returnRole, returnStatus,
                returnAccounts, returnPage, returnOrdersPage, returnWeddingsPage);
        return "redirect:/admin/orders/" + id;
    }

    private void addOrderProfileReturnAttributes(RedirectAttributes redirect, UUID returnUserId,
                                                 String returnQuery, UserRole returnRole,
                                                 UserStatus returnStatus,
                                                 AdminUserFilter.AccountState returnAccounts,
                                                 int returnPage, int returnOrdersPage,
                                                 int returnWeddingsPage) {
        if (returnUserId == null) return;
        redirect.addAttribute("returnUserId", returnUserId);
        redirect.addAttribute("returnQuery", returnQuery == null ? "" : returnQuery);
        if (returnRole != null) redirect.addAttribute("returnRole", returnRole.name());
        if (returnStatus != null) redirect.addAttribute("returnStatus", returnStatus.name());
        redirect.addAttribute("returnAccounts", returnAccounts == null
                ? AdminUserFilter.AccountState.ACTIVE.name() : returnAccounts.name());
        redirect.addAttribute("returnPage", Math.max(0, returnPage));
        redirect.addAttribute("returnOrdersPage", Math.max(0, returnOrdersPage));
        redirect.addAttribute("returnWeddingsPage", Math.max(0, returnWeddingsPage));
    }

    @GetMapping("/admin/payments")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String payments(@RequestParam(defaultValue = "") String q,
                           @RequestParam(required = false) PaymentMethod method,
                           @RequestParam(required = false) PaymentStatus paymentStatus,
                           @RequestParam(required = false) OrderStatus orderStatus,
                           @RequestParam(defaultValue = "0") int page, Authentication authentication, Model model) {
        if (paymentRepository == null) {
            model.addAttribute("payments", org.springframework.data.domain.Page.empty());
        } else {
            model.addAttribute("payments", paymentRepository.searchAdmin(
                    q == null || q.isBlank() ? null : q.trim(), method == null ? null : method.name(),
                    paymentStatus, orderStatus, PageRequest.of(Math.max(page, 0), 20)));
        }
        model.addAttribute("q", q);
        model.addAttribute("method", method == null ? "" : method.name());
        model.addAttribute("paymentStatus", paymentStatus == null ? "" : paymentStatus.name());
        model.addAttribute("orderStatus", orderStatus == null ? "" : orderStatus.name());
        model.addAttribute("methods", PaymentMethod.values());
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("orderStatuses", OrderStatus.values());
        return "admin/payments";
    }

    @GetMapping("/admin/products")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String products(@RequestParam(defaultValue = "") String keyword,
                           @RequestParam(defaultValue = "all") String status,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        var products = adminService.products(keyword, status, Math.max(0, page), 50);
        if (page > 0 && products.getTotalPages() > 0 && page >= products.getTotalPages()) {
            products = adminService.products(keyword, status, products.getTotalPages() - 1, 50);
        }
        model.addAttribute("products", products);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        if (!model.containsAttribute("productForm")) {
            model.addAttribute("productForm", new AdminProductForm());
        }
        AdminProductForm productForm = (AdminProductForm) model.asMap().get("productForm");
        populateProductCategoryOptions(model, productForm == null ? null : productForm.getCategoryId());
        return "admin/products";
    }

    @GetMapping("/admin/products/new")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String newProduct(Model model) {
        AdminProductForm form = new AdminProductForm();
        form.setSkuAutoGenerated(true);
        model.addAttribute("productForm", form);
        model.addAttribute("productId", null);
        return products("", "all", 0, model);
    }

    @GetMapping("/admin/products/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String editProduct(@PathVariable UUID id, Model model) {
        model.addAttribute("productForm", toForm(productService.get(id)));
        model.addAttribute("productId", id);
        return products("", "all", 0, model);
    }

    @PostMapping("/admin/products")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String createProduct(@Valid @ModelAttribute("productForm") AdminProductForm form,
                                BindingResult bindingResult,
                                @RequestParam(name = "image", required = false) MultipartFile image,
                                @RequestParam(defaultValue = "") String keyword,
                                @RequestParam(defaultValue = "all") String status,
                                @RequestParam(defaultValue = "0") int page,
                                Model model, RedirectAttributes redirectAttributes,
                                Authentication authentication, HttpServletRequest request) {
        rejectInvalidCompareAtPrice(form, bindingResult);
        if (bindingResult.hasErrors()) {
            return products(keyword, status, page, model);
        }
        UUID uploadedMediaId = null;
        String originalImageUrl = form.getImageUrl();
        UUID originalManagedMediaId = form.getManagedMediaId();
        try {
            uploadedMediaId = uploadProductImageIfPresent(form, image, actorId(authentication));
            productService.create(actorId(authentication), form, context(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo sản phẩm.");
            return "redirect:/admin/products";
        } catch (RuntimeException exception) {
            abortProductImageIfPresent(uploadedMediaId);
            if (uploadedMediaId != null) {
                form.setImageUrl(originalImageUrl);
                form.setManagedMediaId(originalManagedMediaId);
            }
            bindingResult.reject("product.save.failed", safeProductError(exception));
            return products(keyword, status, page, model);
        }
    }

    @PostMapping("/admin/products/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String updateProduct(@PathVariable UUID id,
                                @Valid @ModelAttribute("productForm") AdminProductForm form,
                                BindingResult bindingResult,
                                @RequestParam(name = "image", required = false) MultipartFile image,
                                @RequestParam(defaultValue = "") String keyword,
                                @RequestParam(defaultValue = "all") String status,
                                @RequestParam(defaultValue = "0") int page,
                                Model model, RedirectAttributes redirectAttributes,
                                Authentication authentication, HttpServletRequest request) {
        rejectInvalidCompareAtPrice(form, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("productId", id);
            return products(keyword, status, page, model);
        }
        UUID uploadedMediaId = null;
        String originalImageUrl = form.getImageUrl();
        UUID originalManagedMediaId = form.getManagedMediaId();
        try {
            uploadedMediaId = uploadProductImageIfPresent(form, image, actorId(authentication));
            productService.update(actorId(authentication), id, form, context(request));
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật sản phẩm.");
            return "redirect:/admin/products";
        } catch (RuntimeException exception) {
            abortProductImageIfPresent(uploadedMediaId);
            if (uploadedMediaId != null) {
                form.setImageUrl(originalImageUrl);
                form.setManagedMediaId(originalManagedMediaId);
            }
            bindingResult.reject("product.save.failed", safeProductError(exception));
            model.addAttribute("productId", id);
            return products(keyword, status, page, model);
        }
    }

    @PostMapping("/admin/products/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String toggleProduct(@PathVariable UUID id, RedirectAttributes redirectAttributes,
                                Authentication authentication, HttpServletRequest request) {
        productService.toggle(actorId(authentication), id, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật trạng thái sản phẩm.");
        return "redirect:/admin/products";
    }

    @PostMapping("/admin/products/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public String deleteProduct(@PathVariable UUID id, RedirectAttributes redirectAttributes,
                                Authentication authentication, HttpServletRequest request) {
        // Keep order history intact: deleting from the catalog is a soft hide.
        productService.hide(actorId(authentication), id, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã ẩn sản phẩm khỏi catalog.");
        return "redirect:/admin/products";
    }

    private AdminProductForm toForm(AdminProductResponse product) {
        AdminProductForm form = new AdminProductForm();
        form.setCategoryId(product.categoryId());
        form.setName(product.name());
        form.setSku(product.sku());
        form.setShortDescription(product.shortDescription());
        form.setCustomizationGuide(product.customizationGuide());
        form.setUnitPrice(product.unitPrice());
        form.setCompareAtPrice(product.compareAtPrice());
        form.setStockQuantity(product.stockQuantity());
        form.setMinOrderQuantity(product.minOrderQuantity());
        form.setWeightGrams(product.weightGrams());
        form.setDimensions(product.dimensions());
        form.setCustomizable(product.customizable());
        form.setDiscountPercent(product.discountPercent());
        form.setDescription(product.description());
        form.setMaterial(product.material());
        form.setImageUrl(product.imageUrl());
        form.setManagedMediaId(product.managedMediaId());
        form.setFeatured(product.featured());
        return form;
    }

    private UUID uploadProductImageIfPresent(AdminProductForm form, MultipartFile image, UUID actorId) {
        if (image == null || image.isEmpty()) return null;
        if (cloudinaryService == null || managedMediaService == null) {
            throw new BusinessConflictException("Dịch vụ tải ảnh sản phẩm chưa sẵn sàng.");
        }
        var uploaded = cloudinaryService.uploadProductImage(image, actorId.toString());
        var media = managedMediaService.trackPublicProductUpload(
                image, actorId, com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID, uploaded);
        form.setImageUrl(uploaded.secureUrl());
        form.setManagedMediaId(media.getId());
        return media.getId();
    }

    private void abortProductImageIfPresent(UUID mediaId) {
        if (mediaId != null && managedMediaService != null) {
            try {
                managedMediaService.abortPublicProductUpload(mediaId);
            } catch (RuntimeException ignored) {
                // The normal cleanup job will reconcile the READY record if queuing cleanup fails.
            }
        }
    }

    private void rejectInvalidCompareAtPrice(AdminProductForm form, BindingResult bindingResult) {
        if (form.getCompareAtPrice() != null
                && form.getCompareAtPrice().compareTo(java.math.BigDecimal.valueOf(form.getUnitPrice())) <= 0) {
            bindingResult.rejectValue("compareAtPrice", "product.compareAtPrice.invalid",
                    "Giá gốc trước giảm phải lớn hơn giá bán.");
        }
    }

    private String safeProductError(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Không thể lưu sản phẩm. Vui lòng kiểm tra lại thông tin." : message;
    }

    private void populateProductCategoryOptions(Model model, Long selectedId) {
        if (categoryRepository == null) {
            model.addAttribute("productCategoryOptions", List.of());
            return;
        }
        var all = categoryRepository.findAll();
        var byId = all.stream().collect(java.util.stream.Collectors.toMap(
                com.utewedding.app.domain.entity.CategoryEntity::getId, category -> category));
        var options = new java.util.ArrayList<ProductCategoryOption>();
        var activeIds = new java.util.HashSet<Long>();
        for (var category : categoryRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc()) {
            activeIds.add(category.getId());
            options.add(new ProductCategoryOption(category.getId(), categoryPath(category, byId)));
        }
        if (selectedId != null && !activeIds.contains(selectedId)) {
            categoryRepository.findById(selectedId).ifPresent(category -> options.add(
                    new ProductCategoryOption(category.getId(), categoryPath(category, byId) + " (đã ngừng)")));
        }
        model.addAttribute("productCategoryOptions", options);
    }

    private String categoryPath(com.utewedding.app.domain.entity.CategoryEntity category,
                                java.util.Map<Long, com.utewedding.app.domain.entity.CategoryEntity> byId) {
        var labels = new java.util.ArrayList<String>();
        var visited = new java.util.HashSet<Long>();
        var cursor = category;
        while (cursor != null && visited.add(cursor.getId())) {
            labels.add(cursor.getName());
            cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
        }
        java.util.Collections.reverse(labels);
        return String.join(" › ", labels);
    }

    private record ProductCategoryOption(Long id, String label) { }

    private UUID actorId(Authentication authentication) {
        UUID id = actorIdOrNull(authentication);
        if (id != null) return id;
        throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                "Không xác định được người dùng hiện tại.");
    }

    private UUID actorIdOrNull(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    private UUID orderIdFromPageRow(Object row) {
        if (row instanceof com.utewedding.app.dto.response.OrderResponse response) return response.id();
        if (row instanceof WebViewModels.AdminOrderView view) return view.getId();
        return null;
    }

    private java.util.Map<UUID, WebViewModels.AdminCheckoutGroupView> checkoutGroupsForRows(
            List<?> rows) {
        if (orderRepository == null || checkoutGroupRepository == null || rows == null || rows.isEmpty()) {
            return java.util.Map.of();
        }
        List<UUID> orderIds = rows.stream().map(this::orderIdFromPageRow)
                .filter(java.util.Objects::nonNull).toList();
        if (orderIds.isEmpty()) return java.util.Map.of();
        return checkoutGroupsForOrderEntities(orderRepository.findAllById(orderIds));
    }

    private WebViewModels.AdminCheckoutGroupView checkoutGroupForOrder(UUID orderId) {
        if (orderRepository == null || checkoutGroupRepository == null) return null;
        return orderRepository.findById(orderId)
                .map(order -> checkoutGroupsForOrderEntities(List.of(order)).get(orderId))
                .orElse(null);
    }

    private java.util.Map<UUID, WebViewModels.AdminCheckoutGroupView> checkoutGroupsForOrderEntities(
            List<com.utewedding.app.domain.entity.OrderEntity> childOrders) {
        if (childOrders == null || childOrders.isEmpty()) return java.util.Map.of();
        java.util.Set<UUID> groupIds = childOrders.stream()
                .map(com.utewedding.app.domain.entity.OrderEntity::getCheckoutGroupId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (groupIds.isEmpty()) return java.util.Map.of();

        java.util.Map<UUID, com.utewedding.app.domain.entity.CheckoutGroupEntity> groups =
                new java.util.HashMap<>();
        checkoutGroupRepository.findAllById(groupIds)
                .forEach(group -> groups.put(group.getId(), group));
        java.util.Map<UUID, List<com.utewedding.app.domain.entity.OrderEntity>> childrenByGroup =
                new java.util.HashMap<>();
        for (UUID groupId : groupIds) {
            childrenByGroup.put(groupId, orderRepository.findByCheckoutGroupIdOrderByCreatedAtAsc(groupId));
        }

        java.util.Map<UUID, WebViewModels.AdminCheckoutGroupView> result = new java.util.LinkedHashMap<>();
        for (var child : childOrders) {
            var group = groups.get(child.getCheckoutGroupId());
            if (group == null) continue;
            List<WebViewModels.AdminOrderSiblingView> siblings = childrenByGroup
                    .getOrDefault(child.getCheckoutGroupId(), List.of()).stream()
                    .filter(candidate -> !child.getId().equals(candidate.getId()))
                    .map(candidate -> new WebViewModels.AdminOrderSiblingView(
                            candidate.getId(), candidate.getOrderNumber()))
                    .toList();
            List<com.utewedding.app.domain.entity.OrderEntity> groupOrders = childrenByGroup
                    .getOrDefault(child.getCheckoutGroupId(), List.of());
            result.put(child.getId(), new WebViewModels.AdminCheckoutGroupView(
                    group.getGroupNumber(), group.getTotalAmount(), group.getPaymentMethod(),
                    group.getPaymentStatus() == null ? null : group.getPaymentStatus().name(),
                    groupOrders.size(), siblings));
        }
        return java.util.Map.copyOf(result);
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
