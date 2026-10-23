package com.utewedding.app.service.web;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.AdminDashboardAnalyticsRepository;
import com.utewedding.app.repository.DashboardDailyRevenueRow;
import com.utewedding.app.repository.DashboardMetricRow;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.repository.AdminUserSearchRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JpaWebAdminService implements WebAdminService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderFulfillmentRepository fulfillmentRepository;
    private final ProductRepository productRepository;
    private final WebCatalogService catalogService;
    private final AdminDashboardAnalyticsRepository dashboardAnalyticsRepository;
    @org.springframework.beans.factory.annotation.Autowired
    public JpaWebAdminService(UserRepository userRepository,
                              OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              OrderFulfillmentRepository fulfillmentRepository,
                              ProductRepository productRepository,
                              WebCatalogService catalogService,
                              AdminDashboardAnalyticsRepository dashboardAnalyticsRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.productRepository = productRepository;
        this.catalogService = catalogService;
        this.dashboardAnalyticsRepository = dashboardAnalyticsRepository;
    }

    @Override
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("revenue", orderRepository.sumTotalAmountByPaymentStatus(PaymentStatus.SUCCEEDED)
                + orderRepository.sumTotalAmountByPaymentStatus(PaymentStatus.PARTIALLY_REFUNDED));
        stats.put("orders", orderRepository.count());
        stats.put("products", catalogServiceProductsCount());
        stats.put("users", userRepository.count());
        stats.put("pending", orderRepository.countByStatus(OrderStatus.PENDING));
        return stats;
    }

    @Override
    public Map<String, Object> dashboardSnapshotStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("products", catalogServiceProductsCount());
        stats.put("users", userRepository.count());
        stats.put("pending", orderRepository.countByStatus(OrderStatus.PENDING));
        return stats;
    }

    @Override
    public DashboardAnalytics dashboardAnalytics(DashboardRange range, ZoneId zoneId) {
        ZoneId effectiveZone = zoneId == null ? DashboardRange.DEFAULT_ZONE : zoneId;
        DashboardRange previousRange = new DashboardRange(range.preset(), range.previousFrom(), range.previousTo());
        List<DashboardMetricRow> aggregates = dashboardAnalyticsRepository.aggregatePeriods(
                range.fromInstant(effectiveZone), range.toExclusiveInstant(effectiveZone),
                previousRange.fromInstant(effectiveZone), previousRange.toExclusiveInstant(effectiveZone));
        Map<String, DashboardMetricRow> byPeriod = aggregates.stream()
                .collect(Collectors.toMap(DashboardMetricRow::getPeriod, row -> row));
        DashboardMetricRow current = byPeriod.get("current");
        DashboardMetricRow previous = byPeriod.get("previous");
        long currentRevenue = current == null ? 0 : current.getRevenue();
        long previousRevenue = previous == null ? 0 : previous.getRevenue();
        List<DashboardDailyRevenueRow> dailyRows = dashboardAnalyticsRepository.findDailyRevenue(
                range.fromInstant(effectiveZone), range.toExclusiveInstant(effectiveZone), effectiveZone.getId());
        Map<LocalDate, Long> dailyAmounts = dailyRows.stream().collect(Collectors.toMap(
                DashboardDailyRevenueRow::getDate, DashboardDailyRevenueRow::getAmount));
        Map<LocalDate, Long> completeDays = new LinkedHashMap<>();
        for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            completeDays.put(date, dailyAmounts.getOrDefault(date, 0L));
        }
        List<RevenuePoint> revenueByDay = completeDays.entrySet().stream()
                .map(entry -> new RevenuePoint(entry.getKey(), entry.getValue())).toList();
        return new DashboardAnalytics(range, previousRange,
                new DashboardMetric(currentRevenue, previousRevenue),
                new DashboardMetric(current == null ? 0 : current.getOrders(),
                        previous == null ? 0 : previous.getOrders()),
                new DashboardMetric(current == null ? 0 : current.getNewUsers(),
                        previous == null ? 0 : previous.getNewUsers()),
                revenueByDay);
    }

    @Override
    public Page<WebViewModels.AdminUserView> users(int page, int size) {
        return users(new AdminUserFilter("", null, null, AdminUserFilter.AccountState.ACTIVE), page, size);
    }

    @Override
    public Page<WebViewModels.AdminUserView> users(AdminUserFilter filter, int page, int size) {
        AdminUserFilter safeFilter = filter == null
                ? new AdminUserFilter("", null, null, AdminUserFilter.AccountState.ACTIVE) : filter;
        Pageable pageable = PageRequest.of(safePage(page), boundedSize(size));
        Page<AdminUserSearchRow> users = userRepository.searchForAdminUsers(
                safeFilter.query(),
                safeFilter.role() == null ? "" : safeFilter.role().name(),
                safeFilter.status() == null ? "" : safeFilter.status().name(),
                safeFilter.accountState().name(), pageable);
        return users.map(user -> new WebViewModels.AdminUserView(
                UUID.fromString(user.getId()), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getStatus(), user.getCreatedAt(), user.getUpdatedAt(), user.getDeletedAt()));
    }

    @Override
    public Page<WebViewModels.ProductView> products(String keyword, String status, int page, int size) {
        return catalogService.productsForAdmin(keyword, status, safePage(page), boundedSize(size));
    }

    @Override
    public Page<WebViewModels.AdminOrderView> orders(int page, int size) {
        Pageable pageable = PageRequest.of(safePage(page), boundedSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return toOrderViews(orderRepository.findAll(pageable));
    }

    @Override
    public List<RevenuePoint> revenueLastSevenDays(ZoneId zoneId) {
        ZoneId effectiveZone = zoneId == null ? ZoneId.of("Asia/Ho_Chi_Minh") : zoneId;
        LocalDate today = LocalDate.now(effectiveZone);
        LocalDate firstDay = today.minusDays(6);
        Instant from = firstDay.atStartOfDay(effectiveZone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(effectiveZone).toInstant();
        Map<LocalDate, Long> amounts = new LinkedHashMap<>();
        for (int offset = 0; offset < 7; offset++) {
            amounts.put(firstDay.plusDays(offset), 0L);
        }
        List<OrderEntity> revenueOrders = orderRepository.findSuccessfulRevenueOrders(from, to);
        if (revenueOrders.isEmpty()) {
            // Legacy rows may not have payment_status populated yet.
            revenueOrders = orderRepository.findRevenueOrders(OrderStatus.PAID, from, to);
        }
        revenueOrders.forEach(order -> {
            Instant effectiveTime = order.getPaidAt() == null ? order.getCreatedAt() : order.getPaidAt();
            if (effectiveTime == null) return;
            LocalDate date = effectiveTime.atZone(effectiveZone).toLocalDate();
            if (amounts.containsKey(date)) {
                amounts.compute(date, (ignored, current) -> Math.addExact(current, order.getTotalAmount()));
            }
        });
        return amounts.entrySet().stream().map(entry -> new RevenuePoint(entry.getKey(), entry.getValue())).toList();
    }

    private Page<WebViewModels.AdminOrderView> toOrderViews(Page<OrderEntity> orders) {
        List<UUID> orderIds = orders.getContent().stream().map(OrderEntity::getId).toList();
        List<UUID> userIds = orders.getContent().stream()
                .map(OrderEntity::getUserId)
                .distinct()
                .toList();
        Map<UUID, String> buyerNames = loadBuyerNames(userIds);
        Map<UUID, String> summaries = orderIds.isEmpty() ? Map.of()
                : orderItemRepository.findByOrderIdInOrderByOrderIdAscIdAsc(orderIds).stream()
                        .collect(Collectors.toMap(OrderItemEntity::getOrderId,
                                OrderItemEntity::getProductName, (first, ignored) -> first));
        return orders.map(order -> toOrderView(order, buyerNames, summaries));
    }

    private Map<UUID, String> loadBuyerNames(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream().collect(Collectors.toMap(
                UserEntity::getId, user -> user.getDisplayName() == null || user.getDisplayName().isBlank()
                        ? "Khách hàng" : user.getDisplayName()));
    }

    private WebViewModels.AdminOrderView toOrderView(OrderEntity order,
                                                     Map<UUID, String> buyerNames,
                                                     Map<UUID, String> summaries) {
        var view = new WebViewModels.AdminOrderView(order.getId(), order.getOrderNumber(),
                buyerNames.getOrDefault(order.getUserId(), "Khách hàng"), order.getShippingPhone(),
                summaries.getOrDefault(order.getId(), "Đơn hàng kỷ vật cưới"),
                order.getTotalAmount(), WebViewModels.AdminOrderStatus.from(order.getStatus()),
                order.getCreatedAt(), order.getShippingAddress(), order.getTrackingNumber(), order.getShipperId());
        view.setPaymentMethod(order.getPaymentMethod());
        return view;
    }

    private long catalogServiceProductsCount() {
        return productRepository.countByActiveTrue();
    }

    private int safePage(int page) {
        return Math.max(page, 0);
    }

    private int boundedSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
