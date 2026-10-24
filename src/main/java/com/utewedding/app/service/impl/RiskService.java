package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
    private final RiskRuleConfigRepository configs;
    private final RiskEventRepository events;
    private final RiskCorrelationRepository correlations;
    private final OrderRepository orders;
    private final ReturnCaseRepository returns;
    private final UserRepository users;
    private final AuditLogPort audit;
    private final ObjectMapper json;
    private final byte[] hmacSecret;

    public RiskService(RiskRuleConfigRepository configs, RiskEventRepository events,
                       RiskCorrelationRepository correlations, OrderRepository orders,
                       ReturnCaseRepository returns, UserRepository users, AuditLogPort audit,
                       ObjectMapper json,
                       @Value("${security.risk.hmac-secret:local-risk-secret-change-me}") String secret) {
        this.configs = configs;
        this.events = events;
        this.correlations = correlations;
        this.orders = orders;
        this.returns = returns;
        this.users = users;
        this.audit = audit;
        this.json = json;
        this.hmacSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public Decision assessOrder(UUID customerId, UUID orderId, long amount) {
        Map<String, RiskRuleConfigEntity> rules = rules();
        OrderEntity order = orders.findById(orderId).orElseThrow();
        Instant now = Instant.now();
        List<Signal> signals = new ArrayList<>();

        RiskRuleConfigEntity velocity = rules.get("ORDER_VELOCITY");
        long hourOrders = orders.countByUserIdAndCreatedAtGreaterThanEqual(customerId, now.minus(Duration.ofHours(1)));
        if (velocity != null && hourOrders > parameter(velocity, "limit", 5)) {
            signals.add(new Signal(velocity, 80, Map.of("count", hourOrders, "windowMinutes", 60)));
        }

        RiskRuleConfigEntity highValue = rules.get("HIGH_VALUE_FIRST_ORDER");
        long totalOrders = orders.countByUserId(customerId);
        if (highValue != null && totalOrders <= 1
                && amount >= parameter(highValue, "amount", 5_000_000)) {
            signals.add(new Signal(highValue, 55, Map.of("amount", amount, "firstOrder", true)));
        }

        RiskRuleConfigEntity codFailures = rules.get("COD_FAILURES");
        int codWindow = parameter(codFailures, "windowDays", 90);
        long failedCod = codFailures == null ? 0 : orders
                .countByUserIdAndPaymentStatusAndCreatedAtGreaterThanEqual(customerId,
                        PaymentStatus.NOT_COLLECTED, now.minus(Duration.ofDays(codWindow)));
        if (codFailures != null && failedCod >= parameter(codFailures, "count", 3)) {
            signals.add(new Signal(codFailures, 85, Map.of("count", failedCod, "windowDays", codWindow)));
        }

        RiskRuleConfigEntity cancellations = rules.get("CANCELLATION_RATE");
        addRateSignal(signals, cancellations, customerId, now, true);
        RiskRuleConfigEntity returnRate = rules.get("RETURN_REFUND_RATE");
        addRateSignal(signals, returnRate, customerId, now, false);
        addVoucherCorrelation(signals, rules.get("VOUCHER_MULTI_ACCOUNT"), customerId, order, now);

        return decideAndRecord(signals, "CUSTOMER", customerId.toString(), orderId);
    }

    /** Called only after a security rate limit has already denied the request. */
    @Transactional
    public void recordAuthRateLimitViolation(String clientIp, String policyName) {
        RiskRuleConfigEntity rule = rules().get("AUTH_RATE_LIMIT");
        if (rule == null) return;
        String subject = correlate(clientIp);
        String safePolicy = policyName == null ? "unknown" : policyName.substring(0, Math.min(40, policyName.length()));
        String bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
        record(new Signal(rule, 75, Map.of("policy", safePolicy, "window", bucket)),
                "IP_HMAC", subject, null, effective(rule, false),
                rule.getRuleCode() + ":" + subject + ":" + safePolicy + ":" + bucket);
    }

    /** In shadow mode effective action is always ALLOW; one weak signal never blocks in enforce mode. */
    public RiskAction effective(RiskRuleConfigEntity rule, boolean compositeHighConfidence) {
        if (rule.getMode() == RiskRuleMode.SHADOW) return RiskAction.ALLOW;
        if (rule.getRecommendedAction() == RiskAction.BLOCK_ACTION && !compositeHighConfidence) {
            return RiskAction.REVIEW;
        }
        return rule.getRecommendedAction();
    }

    @Transactional
    public RiskEventEntity resolve(UUID actor, UUID id, RiskEventStatus status, String note,
                                   RequestContext context) {
        requireStaff(actor);
        if (status == null || status == RiskEventStatus.OPEN) {
            throw new BusinessConflictException("Resolution status không hợp lệ.");
        }
        RiskEventEntity event = events.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy risk event."));
        if (event.getStatus()!=RiskEventStatus.OPEN) throw new BusinessConflictException("Cảnh báo đã được xử lý.");
        if (note != null && note.length()>1000 || status==RiskEventStatus.DISMISSED && (note==null||note.isBlank()))
            throw new BusinessConflictException("Cần ghi chú hợp lệ, tối đa 1000 ký tự.");
        event.resolve(status, actor, note==null?null:note.trim());
        audit.record("RISK_EVENT_RESOLVED", actor, "RiskEvent", id,
                Map.of("status", status.name()), context);
        return event;
    }

    @Transactional(readOnly = true)
    public List<RiskEventEntity> queue(UUID actor) {
        requireStaff(actor);
        return events.findFirst200ByStatusOrderByCreatedAtDesc(RiskEventStatus.OPEN);
    }

    @Transactional
    public int purgeExpiredCorrelations() {
        return correlations.deleteExpired(Instant.now().minus(Duration.ofDays(90)));
    }

    @Transactional(readOnly=true)
    public org.springframework.data.domain.Page<EventView> search(UUID actor, RiskEventStatus status, String rule, Instant from, Instant to, int page) {
        requireStaff(actor);
        return events.findAll((root,query,builder)-> {
            var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(status!=null)predicates.add(builder.equal(root.get("status"),status));
            if(rule!=null&&!rule.isBlank())predicates.add(builder.equal(root.get("ruleCode"),rule));
            if(from!=null)predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"),from));
            if(to!=null)predicates.add(builder.lessThan(root.get("createdAt"),to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        },org.springframework.data.domain.PageRequest.of(Math.max(0,page),20,org.springframework.data.domain.Sort.by("createdAt").descending())).map(this::view);
    }
    @Transactional(readOnly=true) public EventView detail(UUID actor, UUID id) {requireStaff(actor);return view(events.findById(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy cảnh báo.")));}
    public EventView view(RiskEventEntity event) {
        var safe=new java.util.TreeMap<String,Object>();
        try {var evidence=json.readTree(event.getEvidence());evidence.fields().forEachRemaining(entry->{if(java.util.Set.of("count","windowMinutes","amount","firstOrder","fulfillments","disputes","ratePercent","windowDays","orders","accounts","windowHours","hmacOnly").contains(entry.getKey()) && (entry.getValue().isNumber()||entry.getValue().isBoolean()))safe.put(entry.getKey(),json.convertValue(entry.getValue(),Object.class));});}catch(Exception ignored){}
        return new EventView(event.getId(),event.getRuleCode(),event.getSeverity(),event.getScore(),event.getRecommendedAction(),event.getEffectiveAction(),event.getStatus(),event.getCreatedAt(),"Đã ẩn",safe);
    }
    public record EventView(UUID id,String rule,String severity,int score,RiskAction recommendedAction,RiskAction effectiveAction,RiskEventStatus status,Instant createdAt,String subject,Map<String,Object> evidence){}

    public String correlate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void addRateSignal(List<Signal> signals, RiskRuleConfigEntity rule, UUID customerId,
                               Instant now, boolean cancellation) {
        if (rule == null) return;
        int windowDays = parameter(rule, "windowDays", cancellation ? 30 : 90);
        Instant from = now.minus(Duration.ofDays(windowDays));
        long ordersInWindow = orders.countByUserIdAndCreatedAtGreaterThanEqual(customerId, from);
        long incidents = cancellation
                ? orders.countByUserIdAndStatusAndCreatedAtGreaterThanEqual(customerId, OrderStatus.CANCELLED, from)
                : returns.countByCustomerIdAndCreatedAtGreaterThanEqual(customerId, from);
        int rate = ordersInWindow == 0 ? 0 : (int) Math.min(100, incidents * 100 / ordersInWindow);
        if (incidents >= parameter(rule, "count", 3)
                && rate >= parameter(rule, "ratePercent", 50)) {
            signals.add(new Signal(rule, 60, Map.of("count", incidents, "orders", ordersInWindow,
                    "ratePercent", rate, "windowDays", windowDays)));
        }
    }

    private void addVoucherCorrelation(List<Signal> signals, RiskRuleConfigEntity rule, UUID customerId,
                                       OrderEntity order, Instant now) {
        if (rule == null || order.getDiscountCode() == null || order.getDiscountCode().isBlank()) return;
        String voucherHash = correlate(order.getDiscountCode());
        String phoneHash = correlate(order.getShippingPhone());
        String addressHash = correlate(order.getShippingAddress());
        if (!correlations.existsByOrderId(order.getId())) {
            RiskCorrelationEntity row = new RiskCorrelationEntity();
            row.setOrderId(order.getId());
            row.setAccountId(customerId);
            row.setVoucherHash(voucherHash);
            row.setPhoneHash(phoneHash);
            row.setAddressHash(addressHash);
            correlations.saveAndFlush(row);
        }
        int windowHours = parameter(rule, "windowHours", 24);
        long accounts = correlations.countDistinctAccounts(voucherHash, phoneHash, addressHash,
                now.minus(Duration.ofHours(windowHours)));
        if (accounts >= parameter(rule, "accounts", 3)) {
            signals.add(new Signal(rule, 65,
                    Map.of("accounts", accounts, "windowHours", windowHours, "hmacOnly", true)));
        }
    }

    private Decision decideAndRecord(List<Signal> signals, String subjectType, String subjectId, UUID referenceId) {
        int highConfidence = (int) signals.stream().filter(signal -> signal.score() >= 75).count();
        boolean composite = highConfidence >= 2;
        RiskAction decision = RiskAction.ALLOW;
        for (Signal signal : signals) {
            RiskAction action = effective(signal.rule(), composite);
            if (action == RiskAction.BLOCK_ACTION) decision = RiskAction.BLOCK_ACTION;
            else if ((action == RiskAction.REVIEW || action == RiskAction.FLAG) && decision == RiskAction.ALLOW) {
                decision = action;
            }
            String deduplicationKey = signal.rule().getRuleCode() + ":" + referenceId;
            record(signal, subjectType, subjectId, referenceId, action, deduplicationKey);
        }
        if (decision == RiskAction.BLOCK_ACTION) {
            throw new BusinessConflictException("Giao dịch cần được kiểm tra an toàn trước khi tiếp tục.");
        }
        return new Decision(decision, signals.size());
    }

    private void record(Signal signal, String subjectType, String subjectId, UUID referenceId,
                        RiskAction action, String deduplicationKey) {
        if (events.findByDeduplicationKey(deduplicationKey).isPresent()) return;
        RiskEventEntity event = new RiskEventEntity();
        event.setRuleCode(signal.rule().getRuleCode());
        event.setRuleVersion(signal.rule().getVersion());
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectId);
        event.setScore(signal.score());
        event.setSeverity(signal.rule().getSeverity());
        event.setRecommendedAction(signal.rule().getRecommendedAction());
        event.setEffectiveAction(action);
        try { event.setEvidence(json.writeValueAsString(signal.evidence())); }
        catch (Exception ignored) { event.setEvidence("{}"); }
        event.setDeduplicationKey(deduplicationKey);
        events.save(event);
    }

    private Map<String, RiskRuleConfigEntity> rules() {
        return configs.findLatestEnabled().stream().collect(Collectors.toMap(
                RiskRuleConfigEntity::getRuleCode, value -> value));
    }

    private int parameter(RiskRuleConfigEntity rule, String name, int fallback) {
        if (rule == null) return fallback;
        try {
            JsonNode value = json.readTree(rule.getParameters()).get(name);
            return value == null || !value.canConvertToInt() ? fallback : value.asInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private UserEntity requireStaff(UUID id) {
        UserEntity user = users.findById(id).orElseThrow();
        if (user.getStatus()!=UserStatus.ACTIVE || (user.getRole() != UserRole.MANAGER && user.getRole() != UserRole.ADMIN
                && user.getRole() != UserRole.SUPER_ADMIN)) {
            throw new AccessDeniedException("Không có quyền fraud review.");
        }
        return user;
    }

    private record Signal(RiskRuleConfigEntity rule, int score, Map<String, Object> evidence) {}
    public record Decision(RiskAction action, int signalCount) {}
}
