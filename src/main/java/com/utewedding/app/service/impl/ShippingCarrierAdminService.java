package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.ShippingCarrierEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ShippingCarrierRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingCarrierAdminService {
    private static final long MAX_CUSTOMER_FEE = 1_000_000_000_000L;
    private final ShippingCarrierRepository carriers;
    private final AuditLogPort audit;

    public ShippingCarrierAdminService(ShippingCarrierRepository carriers, AuditLogPort audit) {
        this.carriers = carriers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ShippingCarrierEntity> search(String search, String scope, String provider, String state,
                                              Pageable pageable, String actorRole) {
        requireAllowed(actorRole);
        String q = normalizedSearch(search);
        String selectedScope = validFilter(scope, "HCM", "OUTSIDE_HCM");
        String selectedProvider = validFilter(provider, "INTERNAL", "GHN");
        String selectedState = validFilter(state, "ACTIVE", "INACTIVE", "DELETED");
        List<ShippingCarrierEntity> matching = carriers.findAll(
                        Sort.by(Sort.Order.asc("coverageScope"), Sort.Order.asc("code"))).stream()
                .filter(carrier -> q.isEmpty() || contains(carrier.getCode(), q)
                        || contains(carrier.getDisplayName(), q) || contains(carrier.getDescription(), q))
                .filter(carrier -> selectedScope == null || selectedScope.equals(carrier.getCoverageScope()))
                .filter(carrier -> selectedProvider == null || selectedProvider.equals(carrier.getProviderCode()))
                .filter(carrier -> selectedState == null
                        || (selectedState.equals("DELETED") ? carrier.getDeletedAt() != null
                        : carrier.getDeletedAt() == null && carrier.isActive() == selectedState.equals("ACTIVE")))
                .toList();
        int start = (int) Math.min(pageable.getOffset(), matching.size());
        int end = Math.min(start + pageable.getPageSize(), matching.size());
        return new PageImpl<>(matching.subList(start, end), pageable, matching.size());
    }

    @Transactional
    public ShippingCarrierEntity create(CreateCommand command, UUID actorId, RequestContext context) {
        requireAllowed(context);
        String code = normalizeCode(command.code());
        String displayName = required(command.displayName(), "Tên hiển thị");
        String scope = normalizeScope(command.scope());
        String provider = normalizeProvider(command.providerCode());
        validatePair(provider, scope);
        validateFee(command.customerFee());
        if (carriers.existsByCodeIgnoreCase(code))
            throw new BusinessConflictException("Mã cấu hình nhà vận chuyển đã được sử dụng.");
        if (command.defaultForNewOrders() && !command.active())
            throw new BusinessConflictException("Cấu hình mặc định phải đang hoạt động.");

        if (command.defaultForNewOrders()) clearDefaults(scope);
        ShippingCarrierEntity carrier = new ShippingCarrierEntity();
        carrier.setCode(code);
        carrier.setDisplayName(displayName);
        carrier.setDescription(cleanOptional(command.description()));
        carrier.setProviderCode(provider);
        carrier.setCoverageScope(scope);
        carrier.setPricingMode(pricingModeFor(carrier));
        carrier.setCustomerFee(command.customerFee());
        carrier.setActive(command.active());
        carrier.setDefaultForNewOrders(command.defaultForNewOrders());
        carrier = carriers.saveAndFlush(carrier);
        record(actorId, carrier, "CREATED", Map.of("provider", provider, "scope", scope,
                "customerFee", carrier.getCustomerFee(), "default", carrier.isDefaultForNewOrders()), context);
        return carrier;
    }

    @Transactional
    public ShippingCarrierEntity update(UUID id, long expectedVersion, UpdateCommand command,
                                        UUID actorId, RequestContext context) {
        requireAllowed(context);
        ShippingCarrierEntity carrier = lock(id);
        requireVersion(carrier, expectedVersion);
        if (carrier.getDeletedAt() != null)
            throw new BusinessConflictException("Cấu hình đã xóa mềm; hãy khôi phục trước khi chỉnh sửa.");
        String displayName = required(command.displayName(), "Tên hiển thị");
        String description = cleanOptional(command.description());
        validateFee(command.customerFee());
        Map<String, Object> before = state(carrier);
        carrier.setDisplayName(displayName);
        carrier.setDescription(description);
        carrier.setPricingMode(pricingModeFor(carrier));
        carrier.setCustomerFee(command.customerFee());
        carrier = carriers.saveAndFlush(carrier);
        record(actorId, carrier, "UPDATED", Map.of("before", before, "after", state(carrier)), context);
        return carrier;
    }

    @Transactional
    public ShippingCarrierEntity setDefault(UUID id, long expectedVersion, UUID actorId, RequestContext context) {
        requireAllowed(context);
        List<ShippingCarrierEntity> scopeRows = lockScopeFor(id);
        ShippingCarrierEntity current = byId(scopeRows, id);
        requireVersion(current, expectedVersion);
        if (!current.isActive() || current.getDeletedAt() != null)
            throw new BusinessConflictException("Chỉ cấu hình đang hoạt động mới có thể làm mặc định.");
        Map<String, Object> before = state(current);
        scopeRows.forEach(row -> row.setDefaultForNewOrders(false));
        carriers.flush();
        current.setDefaultForNewOrders(true);
        current = carriers.saveAndFlush(current);
        record(actorId, current, "DEFAULT_CHANGED", Map.of("before", before, "after", state(current)), context);
        return current;
    }

    @Transactional
    public ShippingCarrierEntity setActive(UUID id, long expectedVersion, boolean active,
                                           UUID actorId, RequestContext context) {
        requireAllowed(context);
        List<ShippingCarrierEntity> scopeRows = lockScopeFor(id);
        ShippingCarrierEntity current = byId(scopeRows, id);
        requireVersion(current, expectedVersion);
        if (current.getDeletedAt() != null)
            throw new BusinessConflictException("Cấu hình đã xóa mềm; hãy khôi phục trước khi bật/tắt.");
        if (!active && current.isDefaultForNewOrders())
            throw new BusinessConflictException("Hãy chọn cấu hình mặc định thay thế trước khi ngừng cấu hình này.");
        if (current.isActive() == active) return current;
        Map<String, Object> before = state(current);
        if (!active) current.incrementAvailabilityVersion();
        current.setActive(active);
        current = carriers.saveAndFlush(current);
        record(actorId, current, active ? "ACTIVATED" : "DEACTIVATED",
                Map.of("before", before, "after", state(current)), context);
        return current;
    }

    @Transactional
    public ShippingCarrierEntity softDelete(UUID id, long expectedVersion, UUID actorId, RequestContext context) {
        requireAllowed(context);
        List<ShippingCarrierEntity> scopeRows = lockScopeFor(id);
        ShippingCarrierEntity current = byId(scopeRows, id);
        requireVersion(current, expectedVersion);
        if (current.getDeletedAt() != null) return current;
        if (current.isDefaultForNewOrders())
            throw new BusinessConflictException("Hãy chọn cấu hình mặc định thay thế trước khi xóa cấu hình này.");
        Map<String, Object> before = state(current);
        current.incrementAvailabilityVersion();
        current.setActive(false);
        current.setDeletedAt(Instant.now());
        current = carriers.saveAndFlush(current);
        record(actorId, current, "SOFT_DELETED", Map.of("before", before, "after", state(current)), context);
        return current;
    }

    @Transactional
    public ShippingCarrierEntity restore(UUID id, long expectedVersion, UUID actorId, RequestContext context) {
        requireAllowed(context);
        ShippingCarrierEntity current = lock(id);
        requireVersion(current, expectedVersion);
        if (current.getDeletedAt() == null)
            throw new BusinessConflictException("Cấu hình này chưa bị xóa.");
        Map<String, Object> before = state(current);
        current.setDeletedAt(null);
        current.setActive(false);
        current.setDefaultForNewOrders(false);
        current = carriers.saveAndFlush(current);
        record(actorId, current, "RESTORED", Map.of("before", before, "after", state(current)), context);
        return current;
    }

    private void clearDefaults(String scope) {
        List<ShippingCarrierEntity> scopeRows = carriers.lockScope(scope);
        scopeRows.forEach(row -> row.setDefaultForNewOrders(false));
        carriers.flush();
    }

    private List<ShippingCarrierEntity> lockScopeFor(UUID id) {
        ShippingCarrierEntity probe = carriers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cấu hình nhà vận chuyển."));
        return carriers.lockScope(probe.getCoverageScope());
    }

    private ShippingCarrierEntity byId(List<ShippingCarrierEntity> rows, UUID id) {
        return rows.stream().filter(row -> row.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cấu hình nhà vận chuyển."));
    }

    private ShippingCarrierEntity lock(UUID id) {
        return carriers.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cấu hình nhà vận chuyển."));
    }

    private void requireVersion(ShippingCarrierEntity carrier, long expectedVersion) {
        if (carrier.getVersion() != expectedVersion)
            throw new BusinessConflictException("Cấu hình vừa được người khác cập nhật. Hãy tải lại trang rồi thử lại.");
    }

    private void record(UUID actorId, ShippingCarrierEntity carrier, String operation,
                        Map<String, ?> metadata, RequestContext context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", operation);
        details.put("code", carrier.getCode());
        details.put("displayName", carrier.getDisplayName());
        details.putAll(metadata);
        audit.record("SHIPPING_CARRIER_CHANGED", actorId, "SHIPPING_CARRIER", carrier.getId(), details, context);
    }

    private Map<String, Object> state(ShippingCarrierEntity carrier) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("displayName", carrier.getDisplayName());
        state.put("description", carrier.getDescription() == null ? "" : carrier.getDescription());
        state.put("customerFee", carrier.getCustomerFee());
        state.put("active", carrier.isActive());
        state.put("default", carrier.isDefaultForNewOrders());
        state.put("deleted", carrier.getDeletedAt() != null);
        return state;
    }

    private void validatePair(String provider, String scope) {
        boolean supported = ("INTERNAL".equals(provider) && "HCM".equals(scope))
                || ("GHN".equals(provider) && "OUTSIDE_HCM".equals(scope));
        if (!supported) throw new BusinessConflictException(
                "Chỉ hỗ trợ giao nội bộ TP. Hồ Chí Minh (INTERNAL) và giao ngoại tỉnh qua GHN.");
    }

    private String pricingModeFor(ShippingCarrierEntity carrier) {
        if ("INTERNAL-HCM".equals(carrier.getCode()) && "INTERNAL".equals(carrier.getProviderCode())) {
            return "FREE";
        }
        if ("GHN-OUTSIDE".equals(carrier.getCode()) && "GHN".equals(carrier.getProviderCode())) {
            return "PROVIDER_QUOTE";
        }
        return "LEGACY";
    }

    private void requireAllowed(RequestContext context) {
        requireAllowed(context == null ? null : context.actorRole());
    }

    private void requireAllowed(String actorRole) {
        String role = actorRole == null ? "" : actorRole.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MANAGER", "ADMIN", "SUPER_ADMIN").contains(role)) {
            throw new AccessDeniedException("Bạn không có quyền quản lý nhà vận chuyển.");
        }
    }

    private void validateFee(long fee) {
        if (fee < 0 || fee > MAX_CUSTOMER_FEE)
            throw new BusinessConflictException("Phí khách trả phải từ 0 đến 1.000.000.000.000 đồng.");
    }

    private String normalizeCode(String code) {
        String result = required(code, "Mã cấu hình").toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9][A-Z0-9_-]{1,59}"))
            throw new BusinessConflictException("Mã cấu hình chỉ gồm chữ in hoa, số, gạch ngang hoặc gạch dưới.");
        return result;
    }

    private String normalizeScope(String scope) {
        String value = required(scope, "Phạm vi").toUpperCase(Locale.ROOT);
        if (!List.of("HCM", "OUTSIDE_HCM").contains(value))
            throw new BusinessConflictException("Phạm vi giao hàng không hợp lệ.");
        return value;
    }

    private String normalizeProvider(String provider) {
        String value = required(provider, "Đơn vị vận chuyển").toUpperCase(Locale.ROOT);
        if (!List.of("INTERNAL", "GHN").contains(value))
            throw new BusinessConflictException("Đơn vị vận chuyển không được hỗ trợ.");
        return value;
    }

    private String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank()) throw new BusinessConflictException(field + " không được để trống.");
        return result;
    }

    private String cleanOptional(String value) {
        String result = value == null ? "" : value.trim();
        return result.isBlank() ? null : result;
    }

    private String normalizedSearch(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String validFilter(String value, String... allowed) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        return List.of(allowed).contains(candidate) ? candidate : null;
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    public record CreateCommand(String code, String displayName, String description, String providerCode,
                                String scope, long customerFee, boolean active, boolean defaultForNewOrders) { }
    public record UpdateCommand(String displayName, String description, long customerFee) { }
}
