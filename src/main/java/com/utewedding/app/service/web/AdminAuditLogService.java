package com.utewedding.app.service.web;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.entity.AuditLogEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.util.CsvCellEncoder;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditLogService {
    public static final int PAGE_SIZE = 25;
    private static final int EXPORT_BATCH_SIZE = 500;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "from", "to", "status", "percent", "active", "provider", "currency", "count",
            "role", "roles", "totalamount", "discountamount");
    private static final Map<String, String> METADATA_LABELS = Map.ofEntries(
            Map.entry("from", "Trước đó"),
            Map.entry("to", "Sau đó"),
            Map.entry("status", "Trạng thái"),
            Map.entry("percent", "Tỷ lệ hoa hồng (%)"),
            Map.entry("active", "Đang hoạt động"),
            Map.entry("provider", "Nhà cung cấp thanh toán"),
            Map.entry("currency", "Tiền tệ"),
            Map.entry("count", "Số lượng"),
            Map.entry("role", "Vai trò"),
            Map.entry("roles", "Vai trò"),
            Map.entry("totalamount", "Tổng tiền"),
            Map.entry("discountamount", "Số tiền giảm"));

    private final AuditLogRepository auditLogs;
    private final UserRepository users;

    public AdminAuditLogService(AuditLogRepository auditLogs, UserRepository users) {
        this.auditLogs = auditLogs;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogRow> search(AuditLogFilter filter, int page) {
        SearchCriteria criteria = parse(filter);
        Pageable pageable = PageRequest.of(Math.max(0, page), PAGE_SIZE, recentFirstSort());
        Page<AuditLogEntity> result = auditLogs.findAll(specification(criteria), pageable);
        return new PageImpl<>(mapRows(result.getContent()), pageable, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public void validate(AuditLogFilter filter) {
        parse(filter);
    }

    @Transactional(readOnly = true)
    public void writeCsv(AuditLogFilter filter, Writer writer) throws IOException {
        SearchCriteria criteria = parse(filter);
        writer.write("Timestamp,Actor,Actor ID,Role,Action,Resource type,Resource ID,Change summary\r\n");
        int pageNumber = 0;
        Page<AuditLogEntity> batch;
        do {
            batch = auditLogs.findAll(specification(criteria),
                    PageRequest.of(pageNumber++, EXPORT_BATCH_SIZE, recentFirstSort()));
            for (AuditLogRow row : mapRows(batch.getContent())) {
                writeCsvRow(writer, List.of(
                        row.timestamp(), row.actor(), text(row.actorId()), text(row.actorRole()), row.action().name(),
                        text(row.targetType()), text(row.targetReference()), row.summary()));
            }
        } while (batch.hasNext());
    }

    public List<AuditAction> availableActions() {
        return List.of(AuditAction.values());
    }

    public String actionLabel(AuditAction action) {
        return switch (action) {
            case USER_LOGIN_SUCCEEDED -> "Đăng nhập thành công";
            case USER_LOGIN_FAILED -> "Đăng nhập thất bại";
            case USER_LOGOUT_ALL -> "Đăng xuất";
            case USER_REGISTERED -> "Đăng ký tài khoản";
            case USER_ROLES_CHANGED -> "Thay đổi vai trò";
            case USER_PROFILE_UPDATED -> "Cập nhật hồ sơ người dùng";
            case USER_STATUS_CHANGED -> "Thay đổi trạng thái người dùng";
            case USER_DELETED -> "Ngừng hoạt động người dùng";
            case USER_RESTORED -> "Khôi phục người dùng";
            case CATEGORY_CREATED -> "Tạo danh mục";
            case CATEGORY_UPDATED -> "Cập nhật danh mục";
            case CATEGORY_DEACTIVATED -> "Vô hiệu hóa danh mục";
            case VOUCHER_CREATED -> "Tạo voucher";
            case VOUCHER_UPDATED -> "Cập nhật voucher";
            case VOUCHER_DEACTIVATED -> "Vô hiệu hóa voucher";
            case COMMISSION_UPDATED -> "Cập nhật hoa hồng";
            case ORDER_CREATED -> "Tạo đơn hàng";
            case ORDER_STATUS_CHANGED -> "Thay đổi trạng thái đơn hàng";
            case PAYMENT_STATUS_CHANGED -> "Thay đổi trạng thái thanh toán";
            default -> action.name().replace('_', ' ');
        };
    }

    private List<AuditLogRow> mapRows(Collection<AuditLogEntity> entities) {
        Map<UUID, UserEntity> usersById = relatedUsers(entities);
        return entities.stream().map(entity -> toRow(entity, usersById)).toList();
    }

    private Map<UUID, UserEntity> relatedUsers(Collection<AuditLogEntity> entities) {
        List<UUID> userIds = entities.stream()
                .flatMap(entity -> java.util.stream.Stream.of(entity.getActorId(),
                        "User".equalsIgnoreCase(entity.getTargetType()) ? entity.getTargetId() : null))
                .filter(id -> id != null).distinct().toList();
        if (userIds.isEmpty()) return Map.of();
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, user -> user, (left, right) -> left));
    }

    private AuditLogRow toRow(AuditLogEntity entity, Map<UUID, UserEntity> usersById) {
        String actor = entity.getActorId() == null
                ? (entity.getAction() == AuditAction.USER_LOGIN_FAILED ? "ANONYMOUS" : "SYSTEM")
                : usersById.containsKey(entity.getActorId())
                        ? usersById.get(entity.getActorId()).getDisplayName()
                        : "Tài khoản không còn hoạt động";
        String role = entity.getActorRole();
        if (role == null && entity.getActorId() == null) role = actor;
        Map<String, String> metadata = safeMetadata(entity.getMetadata());
        String transition = transition(metadata);
        String summary = transition == null ? actionLabel(entity.getAction())
                : actionLabel(entity.getAction()) + ": " + transition;
        String reference = entity.getTargetReference() != null
                ? entity.getTargetReference()
                : text(entity.getTargetId());
        String targetUrl = "User".equalsIgnoreCase(entity.getTargetType()) && entity.getTargetId() != null
                && usersById.containsKey(entity.getTargetId())
                ? "/admin/users/" + entity.getTargetId()
                : null;
        return new AuditLogRow(entity.getCreatedAt(), TIMESTAMP_FORMAT.withZone(DISPLAY_ZONE).format(entity.getCreatedAt()),
                actor, entity.getActorId(), role, entity.getAction(), actionLabel(entity.getAction()),
                entity.getTargetType(), reference, summary, metadata, targetUrl);
    }

    private Map<String, String> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || value == null) return;
            String normalized = key.toLowerCase(Locale.ROOT);
            if (!SAFE_METADATA_KEYS.contains(normalized) || !(value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Enum<?>)) return;
            String safeValue = String.valueOf(value).trim();
            if (safeValue.isEmpty() || safeValue.length() > 80 || safeValue.contains("@")
                    || safeValue.matches("(?i).*\\b(password|token|secret|credential|bearer)\\b.*")
                    || safeValue.matches("\\+?\\d[\\d .()-]{8,}")) return;
            safe.put(METADATA_LABELS.get(normalized), safeValue);
        });
        return Map.copyOf(safe);
    }

    private String transition(Map<String, String> metadata) {
        String from = metadata.get("Trước đó");
        String to = metadata.get("Sau đó");
        if (from != null && to != null) return from + " → " + to;
        String status = metadata.get("Trạng thái");
        if (status != null) return status;
        if (metadata.containsKey("Tỷ lệ hoa hồng (%)")) return metadata.get("Tỷ lệ hoa hồng (%)") + "%";
        return null;
    }

    private Specification<AuditLogEntity> specification(SearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.actorId() != null) {
                predicates.add(builder.equal(root.get("actorId"), criteria.actorId()));
            } else if ("SYSTEM".equals(criteria.actorKind())) {
                predicates.add(builder.isNull(root.get("actorId")));
                predicates.add(builder.or(builder.equal(root.get("actorRole"), "SYSTEM"),
                        builder.and(builder.isNull(root.get("actorRole")),
                                builder.notEqual(root.get("action"), AuditAction.USER_LOGIN_FAILED))));
            } else if ("ANONYMOUS".equals(criteria.actorKind())) {
                predicates.add(builder.isNull(root.get("actorId")));
                predicates.add(builder.equal(root.get("action"), AuditAction.USER_LOGIN_FAILED));
            }
            if (criteria.action() != null) predicates.add(builder.equal(root.get("action"), criteria.action()));
            if (criteria.resourceType() != null) {
                predicates.add(builder.equal(builder.lower(root.get("targetType")),
                        criteria.resourceType().toLowerCase(Locale.ROOT)));
            }
            if (criteria.resourceId() != null) {
                UUID resourceUuid = parseUuid(criteria.resourceId());
                Predicate referenceMatch = builder.equal(builder.lower(root.get("targetReference")),
                        criteria.resourceId().toLowerCase(Locale.ROOT));
                predicates.add(resourceUuid == null ? referenceMatch : builder.or(
                        builder.equal(root.get("targetId"), resourceUuid), referenceMatch));
            }
            if (criteria.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), criteria.from()));
            if (criteria.until() != null) predicates.add(builder.lessThan(root.get("createdAt"), criteria.until()));
            if (criteria.query() != null) {
                String normalizedQuery = criteria.query().toLowerCase(Locale.ROOT);
                String pattern = "%" + escapeLike(normalizedQuery) + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(builder.like(builder.lower(root.get("targetType")), pattern, '\\'));
                matches.add(builder.like(builder.lower(root.get("targetReference")), pattern, '\\'));
                matches.add(builder.like(builder.lower(root.get("actorRole")), pattern, '\\'));
                List<AuditAction> matchingActions = availableActions().stream()
                        .filter(action -> action.name().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                        .toList();
                if (!matchingActions.isEmpty()) matches.add(root.get("action").in(matchingActions));
                if (criteria.queryUuid() != null) {
                    matches.add(builder.equal(root.get("actorId"), criteria.queryUuid()));
                    matches.add(builder.equal(root.get("targetId"), criteria.queryUuid()));
                }
                predicates.add(builder.or(matches.toArray(Predicate[]::new)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private SearchCriteria parse(AuditLogFilter filter) {
        AuditLogFilter value = filter == null ? new AuditLogFilter() : filter;
        String actorInput = trimmed(value.getActor());
        UUID actorId = null;
        String actorKind = null;
        if (actorInput != null) {
            if ("SYSTEM".equalsIgnoreCase(actorInput) || "ANONYMOUS".equalsIgnoreCase(actorInput)) {
                actorKind = actorInput.toUpperCase(Locale.ROOT);
            } else {
                actorId = parseUuid(actorInput);
                if (actorId == null) throw new IllegalArgumentException("Người thực hiện phải là SYSTEM, ANONYMOUS hoặc UUID hợp lệ.");
            }
        }
        String actionText = trimmed(value.getAction());
        AuditAction action = null;
        if (actionText != null) {
            try {
                action = AuditAction.valueOf(actionText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Loại sự kiện không hợp lệ.");
            }
        }
        String resourceType = maxLength(trimmed(value.getResourceType()), 60, "Loại tài nguyên");
        String resourceId = maxLength(trimmed(value.getResourceId()), 80, "Mã tài nguyên");
        String searchText = maxLength(trimmed(value.getQuery()), 120, "Từ khóa");
        LocalDate fromDate = parseDate(value.getFrom(), "Ngày bắt đầu");
        LocalDate toDate = parseDate(value.getTo(), "Ngày kết thúc");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("Ngày bắt đầu không được sau ngày kết thúc.");
        }
        Instant from = fromDate == null ? null : fromDate.atStartOfDay(DISPLAY_ZONE).toInstant();
        Instant until = toDate == null ? null : toDate.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant();
        return new SearchCriteria(actorId, actorKind, action, resourceType, resourceId, from, until,
                searchText, parseUuid(searchText));
    }

    private LocalDate parseDate(String input, String label) {
        String value = trimmed(input);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(label + " không hợp lệ.");
        }
    }

    private String maxLength(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) throw new IllegalArgumentException(label + " quá dài.");
        return value;
    }

    private String trimmed(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Sort recentFirstSort() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    private void writeCsvRow(Writer writer, List<String> fields) throws IOException {
        writer.write(fields.stream().map(this::csvCell).collect(Collectors.joining(",")));
        writer.write("\r\n");
    }

    private String csvCell(String value) {
        return CsvCellEncoder.cell(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record SearchCriteria(UUID actorId, String actorKind, AuditAction action, String resourceType,
                                  String resourceId, Instant from, Instant until, String query, UUID queryUuid) {
    }
}
