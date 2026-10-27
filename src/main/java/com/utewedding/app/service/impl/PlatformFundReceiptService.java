package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.PlatformFundReceiptEnvironment;
import com.utewedding.app.domain.PlatformFundReceiptSource;
import com.utewedding.app.domain.PlatformFundReceiptSourceKind;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.PaymentProviderEnvironment;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.CodObligationStatus;
import com.utewedding.app.domain.CodRemittanceStatus;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.entity.CheckoutGroupEntity;
import com.utewedding.app.domain.entity.CheckoutPaymentAllocationEntity;
import com.utewedding.app.domain.entity.PlatformFundReceiptAllocationEntity;
import com.utewedding.app.domain.entity.PlatformFundReceiptEntity;
import com.utewedding.app.domain.entity.CodObligationEntity;
import com.utewedding.app.domain.entity.FulfillmentShipmentEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import com.utewedding.app.domain.entity.ShippingProviderAccountEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.CheckoutGroupRepository;
import com.utewedding.app.repository.CheckoutPaymentAllocationRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.repository.PaymentProviderTransactionRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.PlatformFundReceiptAllocationRepository;
import com.utewedding.app.repository.PlatformFundReceiptRepository;
import com.utewedding.app.repository.CodObligationRepository;
import com.utewedding.app.repository.FulfillmentShipmentRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.ShopOrderFinancialSnapshotRepository;
import com.utewedding.app.repository.ShopShippingBindingRepository;
import com.utewedding.app.repository.ShippingProviderAccountRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.finance.PlatformFundReceiptAllocationCommand;
import com.utewedding.app.service.finance.PlatformFundReceiptCommand;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.ShippingProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records money that the platform can evidence as received; customer payment status alone is not a receipt. */
@Service
public class PlatformFundReceiptService {
    private final PlatformFundReceiptRepository receipts;
    private final PlatformFundReceiptAllocationRepository allocations;
    private final UserRepository users;
    private final AuditLogPort audit;
    private final PaymentRepository payments;
    private final PaymentProviderTransactionRepository providerTransactions;
    private final OrderRepository orders;
    private final ShopOrderFinancialSnapshotRepository financialSnapshots;
    private final OrderAppliedDiscountRepository appliedDiscounts;
    private final OrderFulfillmentRepository fulfillments;
    private final FulfillmentShipmentRepository shipments;
    private final CodObligationRepository codObligations;
    private final ShippingProviderAccountRepository shippingAccounts;
    private final ShopShippingBindingRepository shippingBindings;
    private final ShopRepository shops;
    private final ObjectMapper json;
    private OrderShippingSnapshotRepository orderShippingSnapshots;
    private CheckoutGroupRepository checkoutGroups;
    private CheckoutPaymentAllocationRepository checkoutPaymentAllocations;

    @PersistenceContext
    private EntityManager entityManager;

    public PlatformFundReceiptService(PlatformFundReceiptRepository receipts,
                                     PlatformFundReceiptAllocationRepository allocations,
                                     UserRepository users, AuditLogPort audit, PaymentRepository payments,
                                     PaymentProviderTransactionRepository providerTransactions,
                                     OrderRepository orders,
                                     ShopOrderFinancialSnapshotRepository financialSnapshots,
                                     OrderAppliedDiscountRepository appliedDiscounts,
                                     OrderFulfillmentRepository fulfillments,
                                     FulfillmentShipmentRepository shipments,
                                     CodObligationRepository codObligations,
                                     ShippingProviderAccountRepository shippingAccounts,
                                     ShopShippingBindingRepository shippingBindings,
                                     ShopRepository shops, ObjectMapper json) {
        this.receipts = receipts;
        this.allocations = allocations;
        this.users = users;
        this.audit = audit;
        this.payments = payments;
        this.providerTransactions = providerTransactions;
        this.orders = orders;
        this.financialSnapshots = financialSnapshots;
        this.appliedDiscounts = appliedDiscounts;
        this.fulfillments = fulfillments;
        this.shipments = shipments;
        this.codObligations = codObligations;
        this.shippingAccounts = shippingAccounts;
        this.shippingBindings = shippingBindings;
        this.shops = shops;
        this.json = json;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void attachOrderShippingSnapshotRepository(OrderShippingSnapshotRepository repository) {
        orderShippingSnapshots = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachCheckoutGroupRepositories(CheckoutGroupRepository groups,
                                        CheckoutPaymentAllocationRepository allocations) {
        this.checkoutGroups = groups;
        this.checkoutPaymentAllocations = allocations;
    }

    public record CarrierRemittanceOutcome(PlatformFundReceiptEntity receipt,
                                           long reportedCodAmount, long expectedCodAmount) {
        public boolean hasAmountDiscrepancy() { return reportedCodAmount != expectedCodAmount; }
    }

    @Transactional
    public PlatformFundReceiptEntity record(UUID actorId, PlatformFundReceiptCommand command,
                                            RequestContext context) {
        if (command != null && command.sourceCode() == PlatformFundReceiptSource.CARRIER_REMITTANCE) {
            throw new BusinessConflictException(
                    "Biên nhận GHN phải được tạo từ shipment và dữ liệu COD đã lưu.");
        }
        return recordValidated(actorId, command, context);
    }

    private PlatformFundReceiptEntity recordValidated(UUID actorId, PlatformFundReceiptCommand command,
                                                       RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.MANAGER && actor.getRole() != UserRole.ADMIN
                && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ quản trị viên tài chính mới được ghi nhận tiền nền tảng thực nhận.");
        }
        validate(command);

        String provider = normalizeCode(command.providerCode(), 40, "Mã nhà cung cấp");
        String account = required(command.providerAccountReference(), 120, "Mã tài khoản đối soát");
        String identity = required(command.sourceIdentity(), 190, "Mã nguồn đối soát");
        String lockKey = command.sourceCode().name() + "|" + provider + "|" + account + "|"
                + command.environment().name() + "|" + identity;
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", lockKey).getSingleResult();

        var existing = receipts.findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
                command.sourceCode(), provider, account, command.environment(), identity);
        if (existing.isPresent()) {
            PlatformFundReceiptEntity saved = existing.get();
            if (!sameHeader(saved, command, provider, account, identity)
                    || !sameAllocations(allocations.findByReceiptIdOrderByCreatedAtAsc(saved.getId()),
                    command.allocations())) {
                throw new BusinessConflictException("Mã đối soát này đã được ghi nhận với nội dung khác.");
            }
            return saved;
        }

        PlatformFundReceiptEntity receipt = receipts.saveAndFlush(new PlatformFundReceiptEntity(
                command.sourceCode(), provider, account, command.environment(), identity,
                command.grossAmount(), command.feeAmount(), command.deductionAmount(), command.netAmount(),
                optional(command.externalReference(), 190), optional(command.evidenceReference(), 512),
                required(command.evidenceNote(), 1000, "Ghi chú bằng chứng"),
                command.receivedAt() == null ? Instant.now() : command.receivedAt(), actorId));
        List<PlatformFundReceiptAllocationEntity> rows = new ArrayList<>();
        for (PlatformFundReceiptAllocationCommand allocation : command.allocations()) {
            rows.add(new PlatformFundReceiptAllocationEntity(receipt.getId(),
                    required(allocation.allocationKey(), 190, "Mã phân bổ"), allocation.sourceKind(),
                    required(allocation.sourceReference(), 190, "Mã nguồn phân bổ"), allocation.shopId(),
                    allocation.orderId(), allocation.fulfillmentId(), allocation.grossAmount(),
                    allocation.feeAmount(), allocation.deductionAmount(), allocation.netAmount()));
        }
        allocations.saveAllAndFlush(rows);
        audit.record("PLATFORM_FUND_RECEIPT_RECORDED", actorId, "PlatformFundReceipt", receipt.getId(),
                java.util.Map.of("source", command.sourceCode().name(), "provider", provider,
                        "environment", command.environment().name(), "gross", command.grossAmount(),
                        "fees", command.feeAmount(), "deductions", command.deductionAmount(),
                        "net", command.netAmount(), "allocationCount", rows.size()), context);
        return receipt;
    }

    /**
     * Records a GHN remittance for one Vendor shipment. Gross, shop, order, account and environment
     * come from locked persisted records; the Admin can only enter the bank statement fees, deductions
     * and evidence that explain the difference between the collected COD and the net receipt.
     */
    @Transactional
    public CarrierRemittanceOutcome recordCarrierRemittance(UUID actorId, UUID shipmentId,
                                                              long reportedCodAmount, Instant receivedAt,
                                                              long feeAmount,
                                                              long deductionAmount, String externalReference,
                                                              String evidenceReference, String evidenceNote,
                                                              RequestContext context) {
        requireAdmin(actorId, "Chỉ Admin mới được xác nhận biên nhận COD từ GHN.");
        FulfillmentShipmentEntity shipmentProbe = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy shipment GHN."));
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(shipmentProbe.getFulfillmentId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy fulfillment của shipment."));

        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn hàng của shipment."));
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentProbe.getId())
                .orElseThrow(() -> new BusinessConflictException("Fulfillment đã thay đổi."));
        FulfillmentShipmentEntity shipment = shipments.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new BusinessConflictException("Shipment đã thay đổi."));
        if (!shipment.getFulfillmentId().equals(fulfillment.getId())
                || !fulfillment.getOrderId().equals(order.getId())) {
            throw new BusinessConflictException("Liên kết shipment, fulfillment và đơn hàng đã thay đổi.");
        }
        String shipmentProvider = normalizeCode(shipment.getProviderCode(), 40, "Mã nhà cung cấp shipment");
        OrderShippingSnapshotEntity orderShippingSnapshot = orderShippingSnapshots.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessConflictException("Đơn chưa có snapshot nhà cung cấp vận chuyển."));
        String orderProvider = normalizeCode(orderShippingSnapshot.getProviderCode(), 40,
                "Mã nhà cung cấp trong snapshot đơn hàng");
        if (!"GHN".equals(shipmentProvider) || !shipmentProvider.equals(orderProvider)
                || shipment.getProviderAccountId() == null || shipment.getProviderContextSnapshot() == null
                || shipment.getProviderShipmentId() == null || shipment.getProviderShipmentId().isBlank()
                || !order.getShopId().equals(orderShippingSnapshot.getShopId())) {
            throw new BusinessConflictException(
                    "Snapshot nhà cung cấp của đơn và shipment không khớp GHN hoặc thiếu tài khoản đã lưu.");
        }
        if (fulfillment.getStatus() != FulfillmentStatus.DELIVERED
                || shipment.getStatus() != FulfillmentStatus.DELIVERED
                || fulfillment.getDeliveredAt() == null
                || !"COD".equalsIgnoreCase(order.getPaymentMethod())) {
            throw new BusinessConflictException("Chỉ ghi nhận COD GHN đã giao và có trạng thái thanh toán COD.");
        }
        if (!order.getShopId().equals(fulfillment.getShopId())
                || !order.getShopId().equals(shipment.getShopId())) {
            throw new BusinessConflictException("Shop trên shipment không khớp đơn hàng.");
        }
        ShopEntity shop = shops.findById(order.getShopId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy shop của đơn hàng."));
        if (shop.getKind() != ShopKind.VENDOR) {
            throw new BusinessConflictException("Biên nhận GHN này chỉ áp dụng cho COD của Vendor.");
        }

        ShippingProvider.GhnShipmentContext savedContext = readGhnContext(shipment.getProviderContextSnapshot());
        ShopShippingBindingEntity binding = shippingBindings.findByIdAndShopIdForUpdate(
                savedContext.bindingId(), order.getShopId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy binding GHN của shipment."));
        ShippingProviderAccountEntity account = shippingAccounts.findByIdAndProviderCodeForUpdate(
                shipment.getProviderAccountId(), "GHN")
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy tài khoản GHN của shipment."));
        if (!"VERIFIED".equals(account.getStatus()) || account.getVerifiedAt() == null) {
            throw new BusinessConflictException("Tài khoản GHN của shipment chưa được xác minh.");
        }
        if (!order.getShopId().equals(savedContext.shopId())
                || !account.getId().equals(savedContext.accountId())
                || !account.getCredentialReference().equals(savedContext.credentialReference())
                || !Objects.equals(account.getUpdatedAt(), savedContext.accountUpdatedAt())
                || !account.getEnvironment().equals(savedContext.environment())) {
            throw new BusinessConflictException("Snapshot tài khoản GHN không khớp tài khoản đã xác minh.");
        }
        String routeMode = binding.getRouteMode() == null
                ? "LEGACY_ROUTES" : binding.getRouteMode().trim().toUpperCase(Locale.ROOT);
        boolean routePolicyValid = switch (routeMode) {
            case "LEGACY_ROUTES" -> binding.isRouteCoverageVerified();
            case "API_QUOTE" -> "PROVIDER_QUOTE".equals(orderShippingSnapshot.getPricingMode())
                    && Objects.equals(orderShippingSnapshot.getShippingBindingId(), binding.getId())
                    && Objects.equals(orderShippingSnapshot.getShippingBindingRevision(), binding.getRevision())
                    && Objects.equals(orderShippingSnapshot.getShippingProviderAccountId(), account.getId())
                    && Objects.equals(orderShippingSnapshot.getShippingProviderAccountUpdatedAt(), account.getUpdatedAt())
                    && Objects.equals(orderShippingSnapshot.getShippingRouteId(), savedContext.routeId())
                    && Objects.equals(orderShippingSnapshot.getQuoteId(), savedContext.quoteId())
                    && Objects.equals(orderShippingSnapshot.getOriginAddressRevisionId(),
                            savedContext.pickupAddressRevisionId())
                    && Objects.equals(orderShippingSnapshot.getOriginAddressRevision(),
                            savedContext.pickupAddressRevision())
                    && Objects.equals(orderShippingSnapshot.getSelectedGroupFingerprint(),
                            savedContext.selectedGroupFingerprint())
                    && Objects.equals(binding.getPickupAddressRevisionId(), savedContext.pickupAddressRevisionId())
                    && Objects.equals(binding.getReturnAddressRevisionId(), savedContext.returnAddressRevisionId());
            default -> false;
        };
        if (!binding.getProviderAccountId().equals(account.getId())
                || !"VERIFIED".equals(binding.getStatus())
                || !routePolicyValid || !binding.isCodBeneficiaryVerified()
                || binding.getCodBeneficiaryVerifiedAt() == null
                || binding.getCodBeneficiaryEvidenceRedacted() == null
                || !binding.getId().equals(savedContext.bindingId())
                || binding.getRevision() != savedContext.bindingRevision()) {
            throw new BusinessConflictException("Binding hoặc bên nhận tiền COD GHN chưa được xác minh.");
        }

        CodObligationEntity obligation = codObligations.findByFulfillmentIdForUpdate(fulfillment.getId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy nghĩa vụ COD đã lưu."));
        if (obligation.getExpectedCollectable() != savedContext.expectedCodAmount()) {
            throw new BusinessConflictException("Nghĩa vụ COD không khớp số tiền đã lưu trên shipment.");
        }
        if (reportedCodAmount <= 0) {
            throw new BusinessConflictException("Số COD thực thu theo bảng kê GHN phải lớn hơn 0.");
        }
        String external = required(externalReference, 190, "Mã dòng/bảng kê GHN");
        String evidence = required(evidenceReference, 512, "Liên kết hoặc mã bằng chứng GHN");
        String note = required(evidenceNote, 1000, "Ghi chú bằng chứng GHN");
        if (obligation.getRemittanceStatus() == CodRemittanceStatus.RECEIVED) {
            throw new BusinessConflictException("Khoản COD của shipment đã được xác nhận nhận tiền trước đó.");
        }
        boolean recordedCollectionEvidence = false;
        Long previouslyCollected = obligation.getActualCollected();
        if (previouslyCollected != null && previouslyCollected != reportedCodAmount) {
            throw new BusinessConflictException("Số COD theo bảng kê không khớp số thực thu đã ghi nhận trước đó.");
        }
        if (previouslyCollected != null && obligation.getCollectionReference() != null
                && !obligation.getCollectionReference().equals(external)) {
            throw new BusinessConflictException("Tham chiếu COD đã được ghi nhận với bằng chứng khác.");
        }
        if (previouslyCollected == null) {
            if (obligation.getStatus() != CodObligationStatus.PENDING
                    && obligation.getStatus() != CodObligationStatus.AWAITING_AMOUNT) {
                throw new BusinessConflictException("Nghĩa vụ COD không ở trạng thái chờ số thực thu GHN.");
            }
            obligation.setActualCollected(reportedCodAmount);
            obligation.setCollectionReference(external);
            obligation.setSystemReceiptCode(nextCodCollectionReceiptCode());
            obligation.setCollectedAt(fulfillment.getDeliveredAt());
            obligation.setStatus(reportedCodAmount == obligation.getExpectedCollectable()
                    ? CodObligationStatus.COLLECTED
                    : reportedCodAmount < obligation.getExpectedCollectable()
                    ? CodObligationStatus.SHORT : CodObligationStatus.OVER);
            obligation.setRemittanceStatus(CodRemittanceStatus.PENDING);
            obligation.setRemittedAmount(0);
            recordedCollectionEvidence = true;
        } else {
            if (obligation.getSystemReceiptCode() == null) {
                obligation.setSystemReceiptCode(nextCodCollectionReceiptCode());
                recordedCollectionEvidence = true;
            }
            if (obligation.getCollectionReference() == null) {
                obligation.setCollectionReference(external);
                recordedCollectionEvidence = true;
            }
            if (obligation.getCollectedAt() == null) obligation.setCollectedAt(fulfillment.getDeliveredAt());
            if (obligation.getStatus() == CodObligationStatus.AWAITING_AMOUNT
                    || obligation.getStatus() == CodObligationStatus.PENDING) {
                obligation.setStatus(reportedCodAmount == obligation.getExpectedCollectable()
                        ? CodObligationStatus.COLLECTED
                        : reportedCodAmount < obligation.getExpectedCollectable()
                        ? CodObligationStatus.SHORT : CodObligationStatus.OVER);
            }
            obligation.setRemittanceStatus(CodRemittanceStatus.PENDING);
            obligation.setRemittedAmount(0);
        }
        long gross = reportedCodAmount;
        if (receivedAt == null || receivedAt.isAfter(Instant.now().plusSeconds(300))
                || obligation.getCollectedAt() == null || receivedAt.isBefore(obligation.getCollectedAt())
                || feeAmount < 0 || deductionAmount < 0) {
            throw new BusinessConflictException("Thời điểm nhận, phí hoặc khoản khấu trừ không hợp lệ.");
        }
        long deductionsAndFees = safeAdd(feeAmount, deductionAmount);
        if (deductionsAndFees >= gross) {
            throw new BusinessConflictException("Phí và khấu trừ phải nhỏ hơn số COD GHN đã thu.");
        }
        if (recordedCollectionEvidence) {
            audit.record("COD_RECONCILED", actorId, "OrderFulfillment", fulfillment.getId(),
                    java.util.Map.of("source", "GHN_CARRIER_STATEMENT", "actualCollected", gross,
                            "expectedCollectable", obligation.getExpectedCollectable(),
                            "collectionReference", external, "evidenceReference", evidence,
                            "evidenceNote", note, "status", obligation.getStatus().name()), context);
        }
        if (gross != obligation.getExpectedCollectable()) {
            return new CarrierRemittanceOutcome(null, gross, obligation.getExpectedCollectable());
        }
        if (obligation.getStatus() != CodObligationStatus.COLLECTED) {
            throw new BusinessConflictException("Số COD chưa khớp nghĩa vụ đã lưu; biên nhận GHN bị giữ lại.");
        }
        PlatformFundReceiptEnvironment environment = carrierEnvironment(account.getEnvironment());
        String providerAccountReference = account.getId().toString();
        String sourceIdentity = "carrier-remittance:" + shipment.getId();
        String sourceReference = "GHN:" + account.getId() + ":" + shipment.getProviderShipmentId();
        PlatformFundReceiptAllocationCommand allocation = new PlatformFundReceiptAllocationCommand(
                "shipment:" + shipment.getId(), PlatformFundReceiptSourceKind.CARRIER_REMITTANCE,
                sourceReference, order.getShopId(), order.getId(), fulfillment.getId(),
                gross, feeAmount, deductionAmount, gross - deductionsAndFees);
        Instant normalizedReceivedAt = receivedAt.truncatedTo(ChronoUnit.MICROS);
        PlatformFundReceiptCommand command = new PlatformFundReceiptCommand(
                PlatformFundReceiptSource.CARRIER_REMITTANCE, "GHN", providerAccountReference, environment,
                sourceIdentity, gross, feeAmount, deductionAmount, gross - deductionsAndFees,
                external, evidence, note, normalizedReceivedAt, List.of(allocation));

        var existing = receipts
                .findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
                        command.sourceCode(), command.providerCode(), command.providerAccountReference(),
                        command.environment(), command.sourceIdentity());
        if (existing.isEmpty() && (obligation.getRemittanceStatus() == CodRemittanceStatus.RECEIVED
                || allocations.existsByFulfillmentIdAndSourceKindIn(fulfillment.getId(), List.of(
                        PlatformFundReceiptSourceKind.COD_REMITTANCE,
                        PlatformFundReceiptSourceKind.CARRIER_REMITTANCE)))) {
            throw new BusinessConflictException("Khoản COD của shipment đã được phân bổ vào biên nhận khác.");
        }
        return new CarrierRemittanceOutcome(recordValidated(actorId, command, context),
                gross, obligation.getExpectedCollectable());
    }

    @Transactional(readOnly = true)
    public List<PlatformFundReceiptRepository.CarrierRemittanceCandidate> carrierRemittanceCandidates() {
        return receipts.findCarrierRemittanceCandidates();
    }

    private void requireAdmin(UUID actorId, String message) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException(message);
        }
    }

    private ShippingProvider.GhnShipmentContext readGhnContext(String snapshot) {
        try {
            ShippingProvider.GhnShipmentContext context = json.readValue(snapshot,
                    ShippingProvider.GhnShipmentContext.class);
            if (context == null || context.shopId() == null || context.accountId() == null
                    || context.bindingId() == null || context.expectedCodAmount() < 0
                    || context.environment() == null) {
                throw new BusinessConflictException("Snapshot tài khoản GHN của shipment chưa đầy đủ.");
            }
            return context;
        } catch (BusinessConflictException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessConflictException("Không thể xác minh snapshot tài khoản GHN của shipment.");
        }
    }

    private static PlatformFundReceiptEnvironment carrierEnvironment(String environment) {
        if ("PRODUCTION".equals(environment)) return PlatformFundReceiptEnvironment.PRODUCTION;
        throw new BusinessConflictException("Chỉ tài khoản GHN Production mới có thể đối soát tiền thực nhận.");
    }

    @Transactional
    public PlatformFundReceiptEntity recordCodRemittance(UUID actorId, CodObligationEntity obligation,
                                                         OrderFulfillmentEntity fulfillment, long amount,
                                                         String reference, String note, RequestContext context) {
        PlatformFundReceiptAllocationCommand allocation = new PlatformFundReceiptAllocationCommand(
                "fulfillment:" + fulfillment.getId(),
                com.utewedding.app.domain.PlatformFundReceiptSourceKind.COD_REMITTANCE,
                fulfillment.getId().toString(), fulfillment.getShopId(), fulfillment.getOrderId(),
                fulfillment.getId(), amount, 0, 0, amount);
        PlatformFundReceiptCommand command = new PlatformFundReceiptCommand(
                PlatformFundReceiptSource.COD_REMITTANCE,
                "INTERNAL_COD", "COD_CASH_DESK", PlatformFundReceiptEnvironment.INTERNAL,
                obligation.getId().toString(), amount, 0, 0, amount,
                reference, null, note, obligation.getRemittedAt(), List.of(allocation));
        return record(actorId, command, context);
    }

    /**
     * Reconciles one verified gateway capture against an externally evidenced settlement line.
     * Client input can describe the statement, but cannot choose the payment, order, shop or gross amount.
     */
    @Transactional
    public PlatformFundReceiptEntity recordGatewaySettlement(UUID actorId, UUID paymentId,
                                                              String providerAccountReference,
                                                              Instant receivedAt,
                                                              long feeAmount, long deductionAmount,
                                                              String externalReference, String evidenceReference,
                                                              String evidenceNote, RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Admin mới được xác nhận biên nhận đối soát cổng thanh toán.");
        }
        PaymentEntity initial = payments.findById(paymentId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy giao dịch thanh toán."));
        OrderEntity lockedOrder = orders.findByIdForUpdate(initial.getOrderId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn hàng của giao dịch."));
        PaymentEntity payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessConflictException("Giao dịch thanh toán đã thay đổi."));
        if (!lockedOrder.getId().equals(payment.getOrderId())) {
            throw new BusinessConflictException("Đơn hàng của giao dịch đã thay đổi; hãy tải lại trang.");
        }
        String provider = normalizeCode(payment.getProvider(), 40, "Nhà cung cấp");
        if (!provider.equals("VNPAY") && !provider.equals("MOMO")) {
            throw new BusinessConflictException("Chỉ giao dịch VNPAY hoặc MoMo mới được ghi nhận đối soát cổng.");
        }
        if (payment.getStatus() == null || !payment.getStatus().isCaptured()) {
            throw new BusinessConflictException("Giao dịch chưa có trạng thái đã thu tiền.");
        }
        String providerTransactionId = required(payment.getProviderTransactionId(), 190,
                "Mã giao dịch của cổng");
        var capture = providerTransactions
                .findByPaymentIdAndTransactionTypeAndStatusOrderByCreatedAtAsc(
                        paymentId, ProviderTransactionType.CAPTURE, ProviderTransactionStatus.SUCCEEDED)
                .stream()
                .filter(row -> provider.equalsIgnoreCase(row.getProvider()))
                .filter(row -> lockedOrder.getId().equals(row.getOrderId()))
                .filter(row -> providerTransactionId.equals(row.getProviderTransactionId()))
                .filter(row -> "VND".equalsIgnoreCase(row.getCurrency()))
                .filter(row -> row.getAmount() == payment.getAmount())
                .findFirst()
                .orElseThrow(() -> new BusinessConflictException(
                        "Không có bản ghi thu tiền thành công khớp mã và số tiền của giao dịch."));
        PlatformFundReceiptEnvironment environment = receiptEnvironment(capture.getProviderEnvironment());
        var snapshot = financialSnapshots.findByOrderId(lockedOrder.getId())
                .orElseThrow(() -> new BusinessConflictException(
                        "Đơn chưa có ảnh chụp tài chính theo shop; không thể phân bổ biên nhận an toàn."));
        if (!lockedOrder.getShopId().equals(snapshot.getShopId())) {
            throw new BusinessConflictException("Shop trong ảnh chụp tài chính không khớp đơn hàng.");
        }
        if (receivedAt == null || feeAmount < 0 || deductionAmount < 0) {
            throw new BusinessConflictException("Thời điểm nhận tiền, phí và khoản khấu trừ không hợp lệ.");
        }
        long deductionsAndFees = safeAdd(feeAmount, deductionAmount);
        if (deductionsAndFees >= capture.getAmount()) {
            throw new BusinessConflictException("Phí và khấu trừ phải nhỏ hơn số tiền cổng đã thu.");
        }
        if (capture.getProcessedAt() != null && receivedAt.isBefore(capture.getProcessedAt())) {
            throw new BusinessConflictException("Thời điểm nhận tiền không được trước khi cổng xác nhận thu tiền.");
        }
        if (receivedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new BusinessConflictException("Thời điểm nhận tiền không được ở tương lai.");
        }
        String account = required(providerAccountReference, 120, "Mã tài khoản đối soát");
        String external = required(externalReference, 190, "Mã dòng/bảng kê cổng");
        String evidence = required(evidenceReference, 512, "Liên kết hoặc mã bằng chứng");
        String note = required(evidenceNote, 1000, "Ghi chú bằng chứng");
        if (environment == PlatformFundReceiptEnvironment.SANDBOX
                && !note.toUpperCase(Locale.ROOT).contains("SANDBOX")) {
            throw new BusinessConflictException("Biên nhận thử nghiệm phải ghi rõ SANDBOX trong ghi chú.");
        }
        String sourceReference = provider + ":" + providerTransactionId;
        String sourceIdentity = sourceReference;
        PlatformFundReceiptAllocationCommand allocation = new PlatformFundReceiptAllocationCommand(
                "payment:" + payment.getId(), PlatformFundReceiptSourceKind.ONLINE_PAYMENT,
                sourceReference, snapshot.getShopId(), lockedOrder.getId(), null,
                capture.getAmount(), feeAmount, deductionAmount,
                capture.getAmount() - deductionsAndFees);
        PlatformFundReceiptCommand command = new PlatformFundReceiptCommand(
                PlatformFundReceiptSource.GATEWAY_SETTLEMENT, provider, account, environment,
                sourceIdentity, capture.getAmount(), feeAmount, deductionAmount,
                capture.getAmount() - deductionsAndFees, external, evidence, note,
                receivedAt, List.of(allocation));
        var alreadyRecorded = receipts
                .findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
                        PlatformFundReceiptSource.GATEWAY_SETTLEMENT, provider, account, environment, sourceIdentity);
        if (alreadyRecorded.isEmpty() && allocations.existsBySourceKindAndSourceReference(
                PlatformFundReceiptSourceKind.ONLINE_PAYMENT, sourceReference)) {
            throw new BusinessConflictException("Khoản thu của giao dịch này đã được phân bổ vào biên nhận khác.");
        }
        return record(actorId, command, context);
    }

    /**
     * Records one evidenced settlement for a canonical group capture. All child receipt rows are
     * derived from persisted capture allocations; the caller supplies only the statement-level
     * fee/deduction and evidence.
     */
    @Transactional(rollbackFor = Exception.class)
    public PlatformFundReceiptEntity recordGroupedGatewaySettlement(UUID actorId, UUID captureTransactionId,
                                                                    String providerAccountReference,
                                                                    Instant receivedAt,
                                                                    long feeAmount, long deductionAmount,
                                                                    String externalReference,
                                                                    String evidenceReference,
                                                                    String evidenceNote,
                                                                    RequestContext context) {
        requireReceiptAdmin(actorId);
        if (checkoutGroups == null || checkoutPaymentAllocations == null) {
            throw new BusinessConflictException("Phân bổ biên nhận capture nhóm chưa sẵn sàng.");
        }
        PaymentProviderTransactionEntity initial = providerTransactions.findById(captureTransactionId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy capture nhóm."));
        if (initial.getCheckoutGroupId() == null || initial.getOrderId() != null) {
            throw new BusinessConflictException("Giao dịch không thuộc một capture nhóm.");
        }
        CheckoutGroupEntity group = checkoutGroups.findByIdForUpdate(initial.getCheckoutGroupId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy nhóm checkout của capture."));
        PaymentProviderTransactionEntity capture = providerTransactions.findByIdForUpdate(captureTransactionId)
                .orElseThrow(() -> new BusinessConflictException("Capture nhóm đã thay đổi."));
        if (!group.getId().equals(capture.getCheckoutGroupId()) || capture.getOrderId() != null
                || capture.getPaymentId() != null || capture.getTransactionType() != ProviderTransactionType.CAPTURE
                || capture.getStatus() != ProviderTransactionStatus.SUCCEEDED) {
            throw new BusinessConflictException("Chỉ capture nhóm đã xác minh mới được ghi receipt.");
        }
        String provider = normalizeCode(capture.getProvider(), 40, "Nhà cung cấp");
        if (!provider.equals("VNPAY") && !provider.equals("MOMO")) {
            throw new BusinessConflictException("Chỉ giao dịch VNPAY hoặc MoMo mới được ghi nhận đối soát cổng.");
        }
        String groupPaymentMethod = normalizeCode(group.getPaymentMethod(), 40, "Phương thức thanh toán nhóm");
        if (!provider.equals(groupPaymentMethod)) {
            throw new BusinessConflictException("Capture không khớp phương thức thanh toán của nhóm.");
        }
        String providerTransactionId = required(capture.getProviderTransactionId(), 190,
                "Mã giao dịch của cổng");
        if (capture.getAmount() <= 0 || capture.getAmount() != group.getTotalAmount()
                || !"VND".equalsIgnoreCase(capture.getCurrency())
                || !"VND".equalsIgnoreCase(group.getCurrency())) {
            throw new BusinessConflictException("Capture nhóm không khớp tổng VND đã phân bổ.");
        }
        List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(group.getId());
        List<CheckoutPaymentAllocationEntity> allGroupAllocations = checkoutPaymentAllocations
                .findByGroupIdForUpdateOrderByOrderIdAsc(group.getId());
        List<CheckoutPaymentAllocationEntity> captureAllocations = allGroupAllocations.stream()
                .filter(row -> capture.getId().equals(row.getCaptureTransactionId()))
                .sorted(Comparator.comparing(CheckoutPaymentAllocationEntity::getOrderId)).toList();
        if (children.isEmpty() || captureAllocations.size() != children.size()) {
            throw new BusinessConflictException("Capture nhóm chưa có đủ phân bổ cho từng đơn con.");
        }
        Map<UUID, OrderEntity> childById = children.stream()
                .collect(java.util.stream.Collectors.toMap(OrderEntity::getId, value -> value));
        List<GroupReceiptBasis> bases = new ArrayList<>();
        long allocatedGross = 0;
        for (CheckoutPaymentAllocationEntity allocation : captureAllocations) {
            OrderEntity order = childById.get(allocation.getOrderId());
            if (order == null || !group.getId().equals(order.getCheckoutGroupId())
                    || order.getShopId() == null || !provider.equalsIgnoreCase(order.getPaymentMethod())
                    || allocation.getAmount() != order.getTotalAmount()
                    || !"VND".equalsIgnoreCase(allocation.getCurrency())
                    || !"VND".equalsIgnoreCase(order.getCurrency())
                    || !capture.getId().equals(allocation.getCaptureTransactionId())) {
                throw new BusinessConflictException("Phân bổ capture không khớp nhóm hoặc tổng đơn con.");
            }
            PaymentEntity projection = payments.findByCheckoutPaymentAllocationId(allocation.getId())
                    .orElseThrow(() -> new BusinessConflictException("Thiếu PaymentEntity projection của allocation."));
            projection = payments.findByIdForUpdate(projection.getId())
                    .orElseThrow(() -> new BusinessConflictException("PaymentEntity projection đã thay đổi."));
            if (!order.getId().equals(projection.getOrderId())
                    || !allocation.getId().equals(projection.getCheckoutPaymentAllocationId())
                    || projection.getAmount() != allocation.getAmount()
                    || !"VND".equalsIgnoreCase(projection.getCurrency())
                    || projection.getStatus() == null || !projection.getStatus().isCaptured()
                    || !provider.equalsIgnoreCase(projection.getProvider())
                    || projection.getProviderTransactionId() != null) {
                throw new BusinessConflictException("PaymentEntity projection không khớp capture nhóm.");
            }
            var snapshot = financialSnapshots.findByOrderId(order.getId()).orElse(null);
            boolean platformOrder = ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId());
            if (!platformOrder && snapshot == null) {
                throw new BusinessConflictException("Đơn shop chưa có ảnh chụp tài chính để phân bổ biên nhận.");
            }
            if (snapshot != null && !order.getShopId().equals(snapshot.getShopId())) {
                throw new BusinessConflictException("Shop trong ảnh chụp tài chính không khớp đơn hàng.");
            }
            allocatedGross = safeAdd(allocatedGross, allocation.getAmount());
            bases.add(new GroupReceiptBasis(allocation, order));
        }
        if (allocatedGross != capture.getAmount() || allocatedGross != group.getTotalAmount()) {
            throw new BusinessConflictException("Tổng allocation phải khớp capture và tổng nhóm.");
        }
        if (receivedAt == null || feeAmount < 0 || deductionAmount < 0) {
            throw new BusinessConflictException("Thời điểm nhận tiền, phí và khoản khấu trừ không hợp lệ.");
        }
        long costs = safeAdd(feeAmount, deductionAmount);
        if (costs >= capture.getAmount()) {
            throw new BusinessConflictException("Phí và khấu trừ phải nhỏ hơn số tiền cổng đã thu.");
        }
        if (capture.getProcessedAt() != null && receivedAt.isBefore(capture.getProcessedAt())) {
            throw new BusinessConflictException("Thời điểm nhận tiền không được trước khi cổng xác nhận thu tiền.");
        }
        if (receivedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new BusinessConflictException("Thời điểm nhận tiền không được ở tương lai.");
        }
        String account = required(providerAccountReference, 120, "Mã tài khoản đối soát");
        String external = required(externalReference, 190, "Mã dòng/bảng kê cổng");
        String evidence = required(evidenceReference, 512, "Liên kết hoặc mã bằng chứng");
        String note = required(evidenceNote, 1000, "Ghi chú bằng chứng");
        PlatformFundReceiptEnvironment environment = receiptEnvironment(capture.getProviderEnvironment());
        if (environment == PlatformFundReceiptEnvironment.SANDBOX
                && !note.toUpperCase(Locale.ROOT).contains("SANDBOX")) {
            throw new BusinessConflictException("Biên nhận thử nghiệm phải ghi rõ SANDBOX trong ghi chú.");
        }

        Map<UUID, Long> feeShares = proportionalShares(bases, feeAmount,
                basis -> basis.allocation().getAmount());
        Map<UUID, Long> deductionShares = proportionalShares(bases, deductionAmount,
                basis -> basis.allocation().getAmount() - feeShares.get(basis.order().getId()));
        String sourceReference = provider + ":" + providerTransactionId;
        List<PlatformFundReceiptAllocationCommand> receiptAllocations = new ArrayList<>();
        long totalNet = 0;
        for (GroupReceiptBasis basis : bases) {
            long gross = basis.allocation().getAmount();
            long fee = feeShares.get(basis.order().getId());
            long deduction = deductionShares.get(basis.order().getId());
            long net = Math.subtractExact(Math.subtractExact(gross, fee), deduction);
            if (net < 0) throw new BusinessConflictException("Phân bổ phí hoặc khấu trừ vượt tổng đơn.");
            totalNet = safeAdd(totalNet, net);
            receiptAllocations.add(new PlatformFundReceiptAllocationCommand(
                    "checkout-payment-allocation:" + basis.allocation().getId(),
                    PlatformFundReceiptSourceKind.ONLINE_PAYMENT, sourceReference,
                    basis.order().getShopId(), basis.order().getId(), null, gross, fee, deduction, net));
        }
        PlatformFundReceiptCommand command = new PlatformFundReceiptCommand(
                PlatformFundReceiptSource.GATEWAY_SETTLEMENT, provider, account, environment,
                sourceReference, capture.getAmount(), feeAmount, deductionAmount, totalNet,
                external, evidence, note, receivedAt, List.copyOf(receiptAllocations));
        var alreadyRecorded = receipts
                .findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
                        PlatformFundReceiptSource.GATEWAY_SETTLEMENT, provider, account, environment, sourceReference);
        if (alreadyRecorded.isEmpty() && allocations.existsBySourceKindAndSourceReference(
                PlatformFundReceiptSourceKind.ONLINE_PAYMENT, sourceReference)) {
            throw new BusinessConflictException("Khoản thu capture nhóm này đã được phân bổ vào biên nhận khác.");
        }
        return record(actorId, command, context);
    }

    public org.springframework.data.domain.Page<com.utewedding.app.repository.GroupedGatewayReceiptCandidate>
    groupedGatewayReceiptCandidates(org.springframework.data.domain.Pageable pageable) {
        return providerTransactions.findGroupedGatewayReceiptCandidates(ProviderTransactionType.CAPTURE,
                ProviderTransactionStatus.SUCCEEDED, pageable);
    }

    private void requireReceiptAdmin(UUID actorId) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Admin mới được xác nhận biên nhận đối soát cổng thanh toán.");
        }
    }

    private static Map<UUID, Long> proportionalShares(List<GroupReceiptBasis> bases, long amount,
                                                       java.util.function.ToLongFunction<GroupReceiptBasis> weight) {
        if (amount < 0) throw new BusinessConflictException("Khoản phân bổ không hợp lệ.");
        List<GroupReceiptBasis> ordered = bases.stream()
                .sorted(Comparator.comparing(basis -> basis.order().getId())).toList();
        long weightTotal = ordered.stream().mapToLong(weight).reduce(0L, Math::addExact);
        if (amount == 0) return ordered.stream().collect(java.util.stream.Collectors.toMap(
                basis -> basis.order().getId(), basis -> 0L));
        if (weightTotal <= 0 || amount > weightTotal) {
            throw new BusinessConflictException("Không thể phân bổ khoản phí/khấu trừ theo allocation.");
        }
        BigInteger denominator = BigInteger.valueOf(weightTotal);
        Map<UUID, Long> shares = new java.util.LinkedHashMap<>();
        Map<UUID, BigInteger> remainders = new java.util.HashMap<>();
        long allocated = 0;
        for (GroupReceiptBasis basis : ordered) {
            BigInteger numerator = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(weight.applyAsLong(basis)));
            BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
            long share = quotientAndRemainder[0].longValueExact();
            shares.put(basis.order().getId(), share);
            remainders.put(basis.order().getId(), quotientAndRemainder[1]);
            allocated = Math.addExact(allocated, share);
        }
        long remainder = amount - allocated;
        List<GroupReceiptBasis> remainderOrder = ordered.stream()
                .sorted(Comparator.<GroupReceiptBasis, BigInteger>comparing(
                        basis -> remainders.get(basis.order().getId())).reversed()
                        .thenComparing(basis -> basis.order().getId())).toList();
        for (GroupReceiptBasis basis : remainderOrder) {
            if (remainder == 0) break;
            UUID orderId = basis.order().getId();
            long cap = weight.applyAsLong(basis);
            if (shares.get(orderId) >= cap) continue;
            shares.put(orderId, shares.get(orderId) + 1);
            remainder--;
        }
        if (remainder != 0) throw new BusinessConflictException("Không thể cân bằng khoản phí/khấu trừ theo allocation.");
        return shares;
    }

    private record GroupReceiptBasis(CheckoutPaymentAllocationEntity allocation, OrderEntity order) { }

    private static PlatformFundReceiptEnvironment receiptEnvironment(PaymentProviderEnvironment environment) {
        if (environment == null) {
            throw new BusinessConflictException(
                    "Giao dịch cũ chưa lưu môi trường cổng thanh toán; cần đối chiếu nguồn thực nhận riêng.");
        }
        return switch (environment) {
            case SANDBOX -> PlatformFundReceiptEnvironment.SANDBOX;
            case PRODUCTION -> PlatformFundReceiptEnvironment.PRODUCTION;
        };
    }

    /** Records a separately evidenced platform reserve allocated to a platform-funded merchandise discount. */
    @Transactional
    public PlatformFundReceiptEntity recordPlatformSubsidy(UUID actorId, UUID orderId, Instant receivedAt,
                                                            String externalReference, String evidenceReference,
                                                            String evidenceNote, RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Admin mới được xác nhận nguồn trợ giá từ quỹ nền tảng.");
        }
        OrderEntity order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn hàng trợ giá."));
        var snapshot = financialSnapshots.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessConflictException("Đơn chưa có ảnh chụp tài chính theo shop."));
        if (!order.getShopId().equals(snapshot.getShopId())) {
            throw new BusinessConflictException("Shop trong ảnh chụp tài chính không khớp đơn hàng.");
        }
        long subsidy = 0;
        for (var discount : appliedDiscounts.findAllByOrderIdOrderBySlotAsc(orderId)) {
            if (discount.getFundingSource() == VoucherFundingSource.PLATFORM
                    && discount.getVoucherScope() == VoucherScope.PLATFORM
                    && !discount.getVoucherType().isShippingVoucher()) {
                subsidy = safeAdd(subsidy, discount.getDiscountAmount());
            }
        }
        if (subsidy <= 0) {
            throw new BusinessConflictException("Đơn không có voucher hàng hóa do nền tảng tài trợ cần ghi nhận.");
        }
        if (receivedAt == null || receivedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new BusinessConflictException("Thời điểm xác nhận quỹ trợ giá không hợp lệ.");
        }
        String external = required(externalReference, 190, "Mã tham chiếu quỹ nền tảng");
        String evidence = required(evidenceReference, 512, "Liên kết hoặc mã bằng chứng quỹ");
        String note = required(evidenceNote, 1000, "Ghi chú bằng chứng trợ giá");
        String sourceIdentity = "platform-subsidy:" + orderId;
        String sourceReference = "PLATFORM_SUBSIDY";
        PlatformFundReceiptAllocationCommand allocation = new PlatformFundReceiptAllocationCommand(
                "order:" + orderId, PlatformFundReceiptSourceKind.OTHER, sourceReference,
                snapshot.getShopId(), orderId, null, subsidy, 0, 0, subsidy);
        PlatformFundReceiptCommand command = new PlatformFundReceiptCommand(
                PlatformFundReceiptSource.MANUAL, "PLATFORM_RESERVE", "TREASURY_RESERVE",
                PlatformFundReceiptEnvironment.MANUAL, sourceIdentity, subsidy, 0, 0, subsidy,
                external, evidence, note, receivedAt, List.of(allocation));
        var alreadyRecorded = receipts
                .findBySourceCodeAndProviderCodeAndProviderAccountReferenceAndEnvironmentAndSourceIdentity(
                        PlatformFundReceiptSource.MANUAL, "PLATFORM_RESERVE", "TREASURY_RESERVE",
                        PlatformFundReceiptEnvironment.MANUAL, sourceIdentity);
        if (alreadyRecorded.isEmpty() && allocations.existsBySourceKindAndSourceReferenceAndOrderId(
                PlatformFundReceiptSourceKind.OTHER, sourceReference, orderId)) {
            throw new BusinessConflictException("Nguồn trợ giá của đơn đã được phân bổ vào biên nhận khác.");
        }
        return record(actorId, command, context);
    }

    private static void validate(PlatformFundReceiptCommand command) {
        if (command == null || command.sourceCode() == null || command.environment() == null) {
            throw new BusinessConflictException("Nguồn và môi trường nhận tiền là bắt buộc.");
        }
        normalizeCode(command.providerCode(), 40, "Mã nhà cung cấp");
        required(command.providerAccountReference(), 120, "Mã tài khoản đối soát");
        required(command.sourceIdentity(), 190, "Mã nguồn đối soát");
        required(command.evidenceNote(), 1000, "Ghi chú bằng chứng");
        if (command.sourceCode() != PlatformFundReceiptSource.COD_REMITTANCE
                && optional(command.externalReference(), 190) == null
                && optional(command.evidenceReference(), 512) == null) {
            throw new BusinessConflictException("Cần mã tham chiếu hoặc liên kết bằng chứng cho chứng từ đối soát.");
        }
        if (command.grossAmount() <= 0 || command.feeAmount() < 0 || command.deductionAmount() < 0
                || command.netAmount() < 0) {
            throw new BusinessConflictException("Các khoản tiền không hợp lệ.");
        }
        if (safeAdd(safeAdd(command.feeAmount(), command.deductionAmount()), command.netAmount())
                != command.grossAmount()) {
            throw new BusinessConflictException("Tiền gộp phải bằng phí cộng khấu trừ cộng tiền thực nhận.");
        }
        List<PlatformFundReceiptAllocationCommand> items = command.allocations();
        if (items == null || items.isEmpty()) {
            throw new BusinessConflictException("Cần phân bổ chứng từ nhận tiền cho ít nhất một nguồn đơn hàng.");
        }
        Set<String> keys = new HashSet<>();
        long gross = 0;
        long fees = 0;
        long deductions = 0;
        long net = 0;
        for (PlatformFundReceiptAllocationCommand item : items) {
            if (item == null || item.sourceKind() == null || item.shopId() == null) {
                throw new BusinessConflictException("Thông tin phân bổ nguồn nhận tiền chưa đầy đủ.");
            }
            String key = required(item.allocationKey(), 190, "Mã phân bổ");
            if (!keys.add(key)) throw new BusinessConflictException("Mã phân bổ bị lặp trong chứng từ.");
            required(item.sourceReference(), 190, "Mã nguồn phân bổ");
            if (item.grossAmount() < 0 || item.feeAmount() < 0 || item.deductionAmount() < 0
                    || item.netAmount() < 0 || safeAdd(safeAdd(item.feeAmount(), item.deductionAmount()),
                    item.netAmount()) != item.grossAmount()) {
                throw new BusinessConflictException("Số tiền phân bổ không cân đối.");
            }
            gross = safeAdd(gross, item.grossAmount());
            fees = safeAdd(fees, item.feeAmount());
            deductions = safeAdd(deductions, item.deductionAmount());
            net = safeAdd(net, item.netAmount());
        }
        if (gross != command.grossAmount() || fees != command.feeAmount()
                || deductions != command.deductionAmount() || net != command.netAmount()) {
            throw new BusinessConflictException("Tổng phân bổ phải khớp chứng từ nhận tiền.");
        }
        if (command.environment() == PlatformFundReceiptEnvironment.SANDBOX
                && !command.evidenceNote().toUpperCase(Locale.ROOT).contains("SANDBOX")) {
            throw new BusinessConflictException("Bằng chứng Sandbox phải được ghi nhãn SANDBOX rõ ràng.");
        }
    }

    private static boolean sameHeader(PlatformFundReceiptEntity saved, PlatformFundReceiptCommand command,
                                      String provider, String account, String identity) {
        return saved.getProviderCode().equals(provider)
                && saved.getProviderAccountReference().equals(account)
                && saved.getSourceIdentity().equals(identity)
                && saved.getGrossAmount() == command.grossAmount()
                && saved.getFeeAmount() == command.feeAmount()
                && saved.getDeductionAmount() == command.deductionAmount()
                && saved.getNetAmount() == command.netAmount()
                && Objects.equals(saved.getExternalReference(), optional(command.externalReference(), 190))
                && Objects.equals(saved.getEvidenceReference(), optional(command.evidenceReference(), 512))
                && Objects.equals(saved.getEvidenceNote(), required(command.evidenceNote(), 1000, "Ghi chú bằng chứng"))
                && (command.receivedAt() == null || Objects.equals(saved.getReceivedAt(), command.receivedAt()));
    }

    private static boolean sameAllocations(List<PlatformFundReceiptAllocationEntity> saved,
                                           List<PlatformFundReceiptAllocationCommand> requested) {
        if (saved.size() != requested.size()) return false;
        var existingByKey = saved.stream().collect(java.util.stream.Collectors.toMap(
                PlatformFundReceiptAllocationEntity::getAllocationKey, value -> value));
        for (PlatformFundReceiptAllocationCommand row : requested) {
            PlatformFundReceiptAllocationEntity old = existingByKey.get(row.allocationKey());
            if (old == null || old.getSourceKind() != row.sourceKind()
                    || !old.getSourceReference().equals(row.sourceReference())
                    || !old.getShopId().equals(row.shopId()) || !Objects.equals(old.getOrderId(), row.orderId())
                    || !Objects.equals(old.getFulfillmentId(), row.fulfillmentId())
                    || old.getGrossAmount() != row.grossAmount() || old.getFeeAmount() != row.feeAmount()
                    || old.getDeductionAmount() != row.deductionAmount() || old.getNetAmount() != row.netAmount()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeCode(String value, int maxLength, String label) {
        String result = required(value, maxLength, label).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9._-]+")) throw new BusinessConflictException(label + " không hợp lệ.");
        return result;
    }

    private String nextCodCollectionReceiptCode() {
        Number next = (Number) entityManager.createNativeQuery(
                "SELECT nextval('cod_collection_receipt_seq')").getSingleResult();
        String day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        return "COD-" + day + "-" + String.format(Locale.ROOT, "%06d", next.longValue());
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new BusinessConflictException(label + " là bắt buộc.");
        }
        return value.trim();
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > maxLength) throw new BusinessConflictException("Tham chiếu nhận tiền quá dài.");
        return value.trim();
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Tổng tiền đối soát vượt giới hạn hợp lệ.");
        }
    }
}
