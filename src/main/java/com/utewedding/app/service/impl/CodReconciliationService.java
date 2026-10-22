package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class CodReconciliationService {
    private final CodReconciliationRepository reconciliations;
    private final CodObligationRepository obligations;
    private final OrderRepository orders;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderFulfillmentItemRepository links;
    private final OrderItemRepository items;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final AuditLogPort audit;
    private PlatformFundReceiptService platformFundReceipts;
    private AccountingDocumentService accountingDocuments;
    private ShopFinancialService shopFinancialService;
    private OrderShippingSnapshotRepository shippingSnapshots;
    private ShopRepository shops;
    private FulfillmentShipmentRepository shipments;
    @PersistenceContext
    private EntityManager entityManager;

    public CodReconciliationService(CodReconciliationRepository reconciliations,
                                    CodObligationRepository obligations, OrderRepository orders,
                                    OrderFulfillmentRepository fulfillments,
                                    OrderFulfillmentItemRepository links, OrderItemRepository items,
                                    PaymentRepository payments, UserRepository users, AuditLogPort audit) {
        this.reconciliations = reconciliations;
        this.obligations = obligations;
        this.orders = orders;
        this.fulfillments = fulfillments;
        this.links = links;
        this.items = items;
        this.payments = payments;
        this.users = users;
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAccounting(AccountingDocumentService service) { accountingDocuments = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void attachPlatformFundReceipts(PlatformFundReceiptService service) { platformFundReceipts = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachShopFinancialService(ShopFinancialService service) { shopFinancialService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void attachOrderShippingSnapshotRepository(OrderShippingSnapshotRepository repository) {
        shippingSnapshots = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void attachShopRepository(ShopRepository repository) { shops = repository; }

    @org.springframework.beans.factory.annotation.Autowired
    void attachFulfillmentShipmentRepository(FulfillmentShipmentRepository repository) { shipments = repository; }

    @Transactional
    public void initialize(UUID orderId) {
        OrderEntity order = orders.findByIdForUpdate(orderId).orElseThrow();
        if (!PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())
                || reconciliations.findByOrderId(orderId).isPresent()) return;
        List<OrderFulfillmentEntity> rows = fulfillments.findByOrderIdForUpdateOrderByIdAsc(orderId);
        if (rows.isEmpty()) return;
        Map<UUID, OrderItemEntity> itemMap = items.findByOrderIdOrderByIdAsc(orderId).stream()
                .collect(Collectors.toMap(OrderItemEntity::getId, value -> value));
        Map<UUID, Long> merchandise = new LinkedHashMap<>();
        for (OrderFulfillmentEntity fulfillment : rows) {
            long amount = 0;
            for (OrderFulfillmentItemEntity link
                    : links.findByFulfillmentIdOrderByIdAsc(fulfillment.getId())) {
                OrderItemEntity item = itemMap.get(link.getOrderItemId());
                if (item != null) amount = Math.addExact(amount, item.getNetPaidAmount());
            }
            merchandise.put(fulfillment.getId(), amount);
        }
        Map<UUID, Long> shipping = allocate(order.getShippingFee(), merchandise);
        CodReconciliationEntity reconciliation = new CodReconciliationEntity();
        reconciliation.setOrderId(orderId);
        reconciliation.setOriginalDueAmount(order.getTotalAmount());
        reconciliation = reconciliations.saveAndFlush(reconciliation);
        for (OrderFulfillmentEntity fulfillment : rows) {
            CodObligationEntity obligation = new CodObligationEntity();
            obligation.setReconciliationId(reconciliation.getId());
            obligation.setFulfillmentId(fulfillment.getId());
            obligation.setMerchandiseDue(merchandise.get(fulfillment.getId()));
            obligation.setAllocatedShippingFee(shipping.get(fulfillment.getId()));
            obligation.setExpectedCollectable(Math.addExact(merchandise.get(fulfillment.getId()),
                    shipping.get(fulfillment.getId())));
            obligations.save(obligation);
        }
    }

    @Transactional
    public void recordDelivered(UUID orderId, UUID fulfillmentId, Long collected, String reference,
                                RequestContext context) {
        OrderEntity order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return;
        List<OrderFulfillmentEntity> fulfillmentRows =
                fulfillments.findByOrderIdForUpdateOrderByIdAsc(orderId);
        CodReconciliationEntity reconciliation = reconciliations.findByOrderIdForUpdate(orderId).orElse(null);
        if (reconciliation == null || terminal(reconciliation.getStatus())) return;
        List<CodObligationEntity> rows = obligations.findByReconciliationIdForUpdate(reconciliation.getId());
        CodObligationEntity row = rows.stream().filter(value -> value.getFulfillmentId().equals(fulfillmentId))
                .findFirst().orElseThrow();
        if (collected == null) {
            // A null replay is not evidence that supersedes any persisted actual amount,
            // including legacy rows whose receipt code is absent.
            if (row.getActualCollected() != null) return;
            row.setStatus(CodObligationStatus.AWAITING_AMOUNT);
            reconciliation.setStatus(CodReconciliationStatus.AWAITING_AMOUNT);
            return;
        }
        if (collected < 0) throw new BusinessConflictException("Collected COD amount cannot be negative.");
        if (row.getActualCollected() != null || row.getSystemReceiptCode() != null) {
            if (!Objects.equals(row.getActualCollected(), collected)) {
                throw new BusinessConflictException("COD cho kiện này đã được ghi nhận với số tiền khác.");
            }
            String suppliedReference = clean(reference);
            if (suppliedReference != null && !Objects.equals(row.getCollectionReference(), suppliedReference)) {
                throw new BusinessConflictException("Phiếu thu bên ngoài không khớp với lần ghi nhận trước.");
            }
            return;
        }
        row.setActualCollected(collected);
        row.setCollectionReference(clean(reference));
        row.setSystemReceiptCode(nextCollectionReceiptCode());
        row.setRemittanceStatus(collected > 0 ? CodRemittanceStatus.PENDING : CodRemittanceStatus.NOT_DUE);
        row.setRemittedAmount(0);
        row.setCollectedAt(Instant.now());
        row.setStatus(collected == row.getExpectedCollectable() ? CodObligationStatus.COLLECTED
                : collected < row.getExpectedCollectable() ? CodObligationStatus.SHORT : CodObligationStatus.OVER);
        finalizeIfTerminal(order, reconciliation, rows, fulfillmentRows, context);
    }

    @Transactional(readOnly = true)
    public String collectionReceiptCode(UUID fulfillmentId) {
        return obligations.findByFulfillmentId(fulfillmentId)
                .map(CodObligationEntity::getSystemReceiptCode).orElse(null);
    }

    @Transactional(readOnly = true)
    public void validateDeliveredReplay(UUID fulfillmentId, Long collected, String reference) {
        CodObligationEntity row = obligations.findByFulfillmentId(fulfillmentId).orElseThrow(
                () -> new BusinessConflictException("Không tìm thấy bản ghi thu COD của kiện hàng."));
        if (collected == null || !Objects.equals(row.getActualCollected(), collected)
                || row.getSystemReceiptCode() == null) {
            throw new BusinessConflictException("Kiện hàng đã giao với số tiền COD khác lần xác nhận này.");
        }
        String suppliedReference = clean(reference);
        if (suppliedReference != null && !Objects.equals(row.getCollectionReference(), suppliedReference)) {
            throw new BusinessConflictException("Phiếu thu bên ngoài không khớp với lần ghi nhận trước.");
        }
    }

    private String nextCollectionReceiptCode() {
        Number next = (Number) entityManager.createNativeQuery(
                "SELECT nextval('cod_collection_receipt_seq')").getSingleResult();
        String day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        return "COD-" + day + "-" + String.format(Locale.ROOT, "%06d", next.longValue());
    }

    @Transactional
    public void recordReturned(UUID orderId, UUID fulfillmentId, RequestContext context) {
        OrderEntity order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null) return;
        List<OrderFulfillmentEntity> fulfillmentRows =
                fulfillments.findByOrderIdForUpdateOrderByIdAsc(orderId);
        CodReconciliationEntity reconciliation = reconciliations.findByOrderIdForUpdate(orderId).orElse(null);
        if (reconciliation == null || terminal(reconciliation.getStatus())) return;
        List<CodObligationEntity> rows = obligations.findByReconciliationIdForUpdate(reconciliation.getId());
        rows.stream().filter(value -> value.getFulfillmentId().equals(fulfillmentId)).findFirst()
                .ifPresent(row -> {
                    row.setActualCollected(0L);
                    row.setStatus(CodObligationStatus.NOT_COLLECTED);
                    row.setRemittanceStatus(CodRemittanceStatus.NOT_DUE);
                    row.setRemittedAmount(0);
                    row.setCollectedAt(Instant.now());
                });
        finalizeIfTerminal(order, reconciliation, rows, fulfillmentRows, context);
    }

    @Transactional
    public CodObligationEntity receiveRemittance(UUID actorId, UUID fulfillmentId, long receivedAmount,
                                                 String reference, String note, RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.MANAGER && actor.getRole() != UserRole.ADMIN
                && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Không có quyền xác nhận tiền COD từ shipper.");
        }
        if (receivedAmount < 0 || note == null || note.isBlank()) {
            throw new BusinessConflictException("Số tiền nhận và ghi chú xác nhận là bắt buộc.");
        }
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(fulfillmentId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy kiện hàng để đối soát COD."));

        // Match carrier remittance's lock prefix; lock the obligation only after the source guard passes.
        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn hàng để đối soát COD."));
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentId)
                .orElseThrow(() -> new BusinessConflictException("Kiện hàng đã thay đổi."));
        if (!order.getId().equals(fulfillment.getOrderId())
                || !Objects.equals(order.getShopId(), fulfillment.getShopId())) {
            throw new BusinessConflictException("Liên kết đơn hàng và kiện hàng đã thay đổi.");
        }

        ShopEntity shop = shops.findById(order.getShopId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy shop của đơn COD."));
        if (shop.getKind() == ShopKind.VENDOR) {
            OrderShippingSnapshotEntity shippingSnapshot = shippingSnapshots.findByOrderId(order.getId())
                    .orElseThrow(() -> new BusinessConflictException(
                            "Không thể xác minh nguồn vận chuyển của đơn COD Vendor."));
            String providerCode = normalizedShippingProvider(shippingSnapshot.getProviderCode());
            if ("GHN".equals(providerCode)) {
                throw new BusinessConflictException(
                        "COD GHN của Vendor phải được đối soát bằng biên nhận carrier đã xác minh.");
            }
            if (!"INTERNAL".equals(providerCode)) {
                throw new BusinessConflictException(
                        "Không thể xác minh nguồn COD của Vendor cho phép nhận tiền mặt tại quầy.");
            }
        }
        CodObligationEntity obligation = obligations.findByFulfillmentIdForUpdate(fulfillmentId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy khoản thu COD của kiện hàng."));
        if (obligation.getRemittanceStatus() == CodRemittanceStatus.RECEIVED) {
            if (obligation.getRemittedAmount() == receivedAmount) return obligation;
            throw new BusinessConflictException("Khoản COD đã được shop xác nhận với số tiền khác.");
        }
        if (obligation.getRemittanceStatus() != CodRemittanceStatus.PENDING
                || obligation.getActualCollected() == null) {
            throw new BusinessConflictException("Kiện hàng chưa có khoản COD cần shipper nộp lại.");
        }
        if (receivedAmount != obligation.getActualCollected()) {
            throw new BusinessConflictException("Số tiền shop nhận phải khớp số tiền shipper đã thu.");
        }
        String trimmedNote = note.trim();
        obligation.setRemittedAmount(receivedAmount);
        obligation.setRemittanceReference(clean(reference));
        obligation.setRemittanceNote(trimmedNote.substring(0, Math.min(1000, trimmedNote.length())));
        obligation.setRemittedBy(actorId);
        obligation.setRemittedAt(Instant.now());
        obligation.setRemittanceStatus(CodRemittanceStatus.RECEIVED);
        if (platformFundReceipts != null) {
            platformFundReceipts.recordCodRemittance(actorId, obligation, fulfillment, receivedAmount,
                    reference, trimmedNote, context);
        }
        audit.record("COD_RECONCILED", actorId, "OrderFulfillment", fulfillmentId,
                Map.of("remittedAmount", receivedAmount, "remittanceStatus", "RECEIVED"), context);
        return obligation;
    }

    @Transactional
    public PlatformFundReceiptService.CarrierRemittanceOutcome recordCarrierRemittance(
            UUID actorId, UUID shipmentId, long reportedCodAmount, Instant receivedAt,
            long feeAmount, long deductionAmount, String externalReference,
            String evidenceReference, String evidenceNote, RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Admin mới được xác nhận biên nhận COD từ GHN.");
        }
        FulfillmentShipmentEntity shipmentProbe = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy shipment GHN."));
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(shipmentProbe.getFulfillmentId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy fulfillment của shipment."));
        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn hàng của shipment."));
        List<OrderFulfillmentEntity> fulfillmentRows =
                fulfillments.findByOrderIdForUpdateOrderByIdAsc(order.getId());
        if (fulfillmentRows.stream().noneMatch(row -> row.getId().equals(fulfillmentProbe.getId()))) {
            throw new BusinessConflictException("Fulfillment không còn thuộc đơn hàng của shipment.");
        }
        CodReconciliationEntity reconciliationProbe = reconciliations.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đối soát COD của đơn hàng."));
        boolean aggregateAlreadyFinalized = reconciliationProbe.getStatus() == CodReconciliationStatus.RECONCILED
                || reconciliationProbe.getStatus() == CodReconciliationStatus.RESOLVED;
        if (terminal(reconciliationProbe.getStatus()) && !aggregateAlreadyFinalized) {
            throw new BusinessConflictException("Đối soát COD đã kết thúc; không thể ghi nhận thêm số tiền GHN.");
        }

        // Delivery can finalize the order aggregate before GHN publishes statement evidence.
        // The receipt service revalidates the persisted shipment/account context and is idempotent
        // by source identity; do not re-finalize an already closed aggregate.
        PlatformFundReceiptService.CarrierRemittanceOutcome outcome = platformFundReceipts
                .recordCarrierRemittance(actorId, shipmentId, reportedCodAmount, receivedAt, feeAmount,
                        deductionAmount, externalReference, evidenceReference, evidenceNote, context);
        if (aggregateAlreadyFinalized) return outcome;

        CodReconciliationEntity reconciliation = reconciliations.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đối soát COD của đơn hàng."));
        List<CodObligationEntity> rows = obligations.findByReconciliationIdForUpdate(reconciliation.getId());
        finalizeIfTerminal(order, reconciliation, rows, fulfillmentRows, context);
        return outcome;
    }

    private void finalizeIfTerminal(OrderEntity order, CodReconciliationEntity reconciliation,
                                    List<CodObligationEntity> rows,
                                    List<OrderFulfillmentEntity> fulfillmentRows, RequestContext context) {
        if (fulfillmentRows.isEmpty() || fulfillmentRows.stream().anyMatch(row -> !row.getStatus().isTerminal())) return;
        if (rows.stream().anyMatch(row -> row.getStatus() == CodObligationStatus.PENDING
                || row.getStatus() == CodObligationStatus.AWAITING_AMOUNT)) {
            reconciliation.setStatus(CodReconciliationStatus.AWAITING_AMOUNT);
            return;
        }
        Set<UUID> delivered = fulfillmentRows.stream()
                .filter(row -> row.getStatus() == FulfillmentStatus.DELIVERED)
                .map(OrderFulfillmentEntity::getId).collect(Collectors.toSet());
        long due = rows.stream().filter(row -> delivered.contains(row.getFulfillmentId()))
                .mapToLong(CodObligationEntity::getExpectedCollectable).sum();
        long collected = rows.stream().map(CodObligationEntity::getActualCollected).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        reconciliation.setFinalDueAmount(due);
        reconciliation.setCollectedAmount(collected);
        UUID orderId = order.getId();
        PaymentEntity payment = codPayment(orderId);
        payment.setFinalDueAmount(due);
        payment.setCollectedAmount(collected);
        if (due == 0 && collected == 0) {
            reconciliation.setStatus(CodReconciliationStatus.NOT_COLLECTED);
            payment.transitionTo(PaymentStatus.NOT_COLLECTED);
            order.transitionTo(OrderStatus.CANCELLED);
            order.setPaymentStatus(PaymentStatus.NOT_COLLECTED);
            order.setCancelledAt(Instant.now());
            order.setCancelReason("COD_ALL_PACKAGES_UNDELIVERED");
        } else if (due == collected) {
            reconciliation.setStatus(CodReconciliationStatus.RECONCILED);
            payment.transitionTo(PaymentStatus.SUCCEEDED);
            payment.setProcessedAt(Instant.now());
            order.transitionTo(OrderStatus.PAID);
            order.setPaymentStatus(PaymentStatus.SUCCEEDED);
            order.setPaidAt(Instant.now());
            if (accountingDocuments != null) {
                accountingDocuments.ensure(order, AccountingDocumentType.PAYMENT_RECEIPT,
                        "COD-" + reconciliation.getId(), collected);
            }
            if (shopFinancialService != null) shopFinancialService.recordSale(order);
        } else {
            reconciliation.setStatus(CodReconciliationStatus.REVIEW_REQUIRED);
            payment.transitionTo(PaymentStatus.RECONCILIATION_REQUIRED);
            order.transitionTo(OrderStatus.PAYMENT_REVIEW);
            order.setPaymentStatus(PaymentStatus.RECONCILIATION_REQUIRED);
        }
        payments.save(payment);
        orders.save(order);
        audit.record("COD_RECONCILED", null, "Order", orderId,
                Map.of("finalDue", due, "collected", collected,
                        "status", reconciliation.getStatus().name()), context);
    }

    @Transactional
    public CodReconciliationEntity resolve(UUID actorId, UUID orderId, long verifiedCollected,
                                           String reference, String note, RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.MANAGER && actor.getRole() != UserRole.ADMIN
                && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Không có quyền đối soát COD.");
        }
        if (verifiedCollected < 0 || reference == null || reference.isBlank()
                || note == null || note.isBlank()) {
            throw new BusinessConflictException("Số tiền, tham chiếu và ghi chú đối soát là bắt buộc.");
        }
        OrderEntity order = orders.findByIdForUpdate(orderId).orElseThrow();
        CodReconciliationEntity reconciliation = reconciliations.findByOrderIdForUpdate(orderId).orElseThrow();
        if (reconciliation.getStatus() == CodReconciliationStatus.RESOLVED) return reconciliation;
        if (reconciliation.getStatus() != CodReconciliationStatus.REVIEW_REQUIRED
                && reconciliation.getStatus() != CodReconciliationStatus.AWAITING_AMOUNT) {
            throw new BusinessConflictException("COD không ở trạng thái cần đối soát.");
        }
        if (verifiedCollected != reconciliation.getFinalDueAmount()) {
            throw new BusinessConflictException(
                    "Số tiền xác minh phải khớp final due; thiếu/thừa cần tiếp tục điều tra.");
        }
        PaymentEntity payment = codPayment(orderId);
        String trimmedNote = note.trim();
        reconciliation.setCollectedAmount(verifiedCollected);
        reconciliation.resolve(actorId, clean(reference),
                trimmedNote.substring(0, Math.min(1000, trimmedNote.length())));
        payment.setCollectedAmount(verifiedCollected);
        payment.setFinalDueAmount(reconciliation.getFinalDueAmount());
        payment.transitionTo(PaymentStatus.SUCCEEDED);
        payment.setProcessedAt(Instant.now());
        order.transitionTo(OrderStatus.PAID);
        order.setPaymentStatus(PaymentStatus.SUCCEEDED);
        order.setPaidAt(Instant.now());
        if (accountingDocuments != null) {
            accountingDocuments.ensure(order, AccountingDocumentType.PAYMENT_RECEIPT,
                    "COD-RESOLVE-" + reconciliation.getId(), verifiedCollected);
        }
        if (shopFinancialService != null) shopFinancialService.recordSale(order);
        audit.record("COD_RECONCILED", actorId, "Order", orderId,
                Map.of("finalDue", reconciliation.getFinalDueAmount(), "collected", verifiedCollected,
                        "status", "RESOLVED", "reference", clean(reference)), context);
        return reconciliation;
    }

    static Map<UUID, Long> allocate(long total, Map<UUID, Long> weights) {
        if (total < 0 || weights.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("COD allocation amounts must be non-negative");
        }
        LinkedHashMap<UUID, Long> allocated = new LinkedHashMap<>();
        if (weights.isEmpty()) return allocated;
        long sum = weights.values().stream().reduce(0L, Math::addExact);
        if (sum == 0) {
            boolean first = true;
            for (UUID id : weights.keySet()) {
                allocated.put(id, first ? total : 0L);
                first = false;
            }
            return allocated;
        }
        record Remainder(UUID id, BigInteger value) {}
        List<Remainder> remainders = new ArrayList<>();
        long assigned = 0;
        BigInteger divisor = BigInteger.valueOf(sum);
        for (Map.Entry<UUID, Long> entry : weights.entrySet()) {
            BigInteger[] division = BigInteger.valueOf(total).multiply(BigInteger.valueOf(entry.getValue()))
                    .divideAndRemainder(divisor);
            long base = division[0].longValueExact();
            allocated.put(entry.getKey(), base);
            assigned = Math.addExact(assigned, base);
            remainders.add(new Remainder(entry.getKey(), division[1]));
        }
        remainders.sort(Comparator.comparing(Remainder::value).reversed().thenComparing(Remainder::id));
        for (long index = 0; index < total - assigned; index++) {
            UUID id = remainders.get((int) (index % remainders.size())).id();
            allocated.put(id, Math.addExact(allocated.get(id), 1));
        }
        return allocated;
    }

    private PaymentEntity codPayment(UUID orderId) {
        return payments.findByOrderIdForUpdateOrderByIdAsc(orderId).stream()
                .filter(payment -> PaymentMethod.COD.name().equalsIgnoreCase(payment.getProvider()))
                .findFirst().orElseThrow();
    }

    private boolean terminal(CodReconciliationStatus status) {
        return status == CodReconciliationStatus.RECONCILED
                || status == CodReconciliationStatus.NOT_COLLECTED
                || status == CodReconciliationStatus.RESOLVED;
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > 190 ? trimmed.substring(0, 190) : trimmed;
    }

    private String normalizedShippingProvider(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new BusinessConflictException("Thiếu mã nhà cung cấp vận chuyển trong snapshot COD Vendor.");
        }
        String normalized = providerCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._-]{1,40}")) {
            throw new BusinessConflictException("Mã nhà cung cấp vận chuyển trong snapshot COD Vendor không hợp lệ.");
        }
        return normalized;
    }
}
