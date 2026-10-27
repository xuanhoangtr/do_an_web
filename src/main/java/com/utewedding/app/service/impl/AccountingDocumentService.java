package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.AccountingDocumentType;
import com.utewedding.app.domain.entity.AccountingDocumentEntity;
import com.utewedding.app.domain.entity.AccountingDocumentLineEntity;
import com.utewedding.app.domain.entity.AccountingDocumentSellerEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import com.utewedding.app.domain.entity.ReturnCaseItemEntity;
import com.utewedding.app.domain.entity.ReturnResolutionEntity;
import com.utewedding.app.domain.entity.ReturnResolutionItemEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.AccountingDocumentLineRepository;
import com.utewedding.app.repository.AccountingDocumentRepository;
import com.utewedding.app.repository.AccountingDocumentSellerRepository;
import com.utewedding.app.repository.PaymentProviderTransactionRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ReturnCaseItemRepository;
import com.utewedding.app.repository.ReturnCaseRepository;
import com.utewedding.app.repository.ReturnResolutionItemRepository;
import com.utewedding.app.repository.ReturnResolutionRepository;
import com.utewedding.app.domain.ProviderTransactionType;
import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountingDocumentService {
    private final AccountingDocumentRepository documents;
    private final AccountingDocumentSellerRepository sellers;
    private final AccountingDocumentLineRepository lines;
    private final OrderItemRepository orderItems;
    private final ObjectMapper mapper;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentProviderTransactionRepository providerTransactions;
    private final ReturnResolutionRepository resolutions;
    private final ReturnResolutionItemRepository resolutionItems;
    private final ReturnCaseRepository returnCases;
    private final ReturnCaseItemRepository returnCaseItems;

    public AccountingDocumentService(AccountingDocumentRepository documents,
                                     AccountingDocumentSellerRepository sellers,
                                     AccountingDocumentLineRepository lines,
                                     OrderItemRepository orderItems,
                                     OrderRepository orders,
                                     PaymentRepository payments,
                                     PaymentProviderTransactionRepository providerTransactions,
                                     ReturnResolutionRepository resolutions,
                                     ReturnResolutionItemRepository resolutionItems,
                                     ReturnCaseRepository returnCases,
                                     ReturnCaseItemRepository returnCaseItems,
                                     ObjectMapper mapper) {
        this.documents = documents;
        this.sellers = sellers;
        this.lines = lines;
        this.orderItems = orderItems;
        this.orders = orders;
        this.payments = payments;
        this.providerTransactions = providerTransactions;
        this.resolutions = resolutions;
        this.resolutionItems = resolutionItems;
        this.returnCases = returnCases;
        this.returnCaseItems = returnCaseItems;
        this.mapper = mapper;
    }

    @Transactional
    public AccountingDocumentEntity ensure(OrderEntity order, AccountingDocumentType type,
                                           String paymentReference) {
        if (type == AccountingDocumentType.CREDIT_NOTE) {
            throw new IllegalArgumentException("Credit notes require the exact refund amount.");
        }
        return ensure(order, type, paymentReference, order.getTotalAmount());
    }

    @Transactional
    public AccountingDocumentEntity ensure(OrderEntity order, AccountingDocumentType type,
                                           String paymentReference, long documentAmount) {
        if (documentAmount < 0) throw new IllegalArgumentException("Document amount cannot be negative.");
        Optional<AccountingDocumentEntity> existing = type == AccountingDocumentType.CREDIT_NOTE
                ? documents.findFirstByOrderIdAndDocumentTypeAndPaymentReferenceOrderByIssuedAtDesc(
                        order.getId(), type, paymentReference)
                : documents.findFirstByOrderIdAndDocumentTypeOrderByIssuedAtDesc(order.getId(), type);
        if (existing.isPresent()) return existing.get();

        if (type == AccountingDocumentType.CREDIT_NOTE) {
            ReturnResolutionEntity resolution = returnResolution(order.getId(), paymentReference).orElse(null);
            if (resolution != null) {
                return ensureReturnCreditNote(order, resolution, paymentReference, documentAmount);
            }
        }

        List<OrderItemEntity> rows = orderItems.findByOrderIdOrderByIdAsc(order.getId());
        AccountingDocumentEntity document = new AccountingDocumentEntity();
        document.setOrderId(order.getId());
        document.setDocumentType(type);
        document.setDocumentNumber(prefix(type) + order.getOrderNumber() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        document.setBuyerSnapshot(json(Map.of(
                "recipientName", safe(order.getShippingRecipientName()),
                "phone", safe(order.getShippingPhone()),
                "address", safe(order.getShippingAddress()),
                "province", safe(order.getShippingProvinceName()))));
        document.setSubtotal(order.getSubtotal());
        document.setProductDiscount(rows.stream().mapToLong(OrderItemEntity::getDiscountAmount).sum());
        document.setVoucherDiscount(Math.max(0, order.getDiscountAmount() - document.getProductDiscount()));
        document.setShippingFee(order.getShippingFee());
        document.setShippingFeeBeforeDiscount(order.getShippingFeeBeforeDiscount());
        document.setShippingDiscountAmount(order.getShippingDiscountAmount());
        document.setTotalAmount(documentAmount);
        document.setCurrency(order.getCurrency());
        document.setPaymentMethod(order.getPaymentMethod());
        document.setPaymentReference(paymentReference);
        document.setIssuedAt(Instant.now());
        document = documents.saveAndFlush(document);

        AccountingDocumentSellerEntity seller = new AccountingDocumentSellerEntity();
        seller.setDocumentId(document.getId());
        seller.setSellerSnapshot(json(Map.of(
                "shopName", "UTEWedding",
                "legalName", "",
                "taxCode", "",
                "businessAddress", "")));
        seller = sellers.save(seller);

        for (OrderItemEntity item : rows) {
            AccountingDocumentLineEntity line = new AccountingDocumentLineEntity();
            line.setDocumentId(document.getId());
            line.setSellerId(seller.getId());
            line.setOrderItemId(item.getId());
            line.setProductId(item.getProductId());
            line.setProductName(item.getProductName());
            line.setQuantity(item.getQuantity());
            line.setUnitPrice(item.getUnitPrice());
            line.setProductDiscount(item.getDiscountAmount());
            line.setVoucherDiscount(item.getVoucherDiscountAllocated());
            line.setLineTotal(item.getLineTotal());
            lines.save(line);
        }
        return document;
    }

    private Optional<ReturnResolutionEntity> returnResolution(UUID orderId, String paymentReference) {
        if (paymentReference == null || paymentReference.isBlank()) return Optional.empty();
        Optional<ReturnResolutionEntity> manual = resolutions.findByManualReference(paymentReference);
        if (manual.isPresent()) return manual;
        for (PaymentProviderTransactionEntity transaction : providerTransactions.findByOrderIdOrderByCreatedAtAsc(orderId)) {
            if (transaction.getTransactionType() == ProviderTransactionType.REFUND
                    && (paymentReference.equals(transaction.getProviderTransactionId())
                    || paymentReference.equals(transaction.getIdempotencyKey()))
                    && transaction.getReturnResolutionId() != null) {
                return resolutions.findById(transaction.getReturnResolutionId());
            }
        }
        return Optional.empty();
    }

    private AccountingDocumentEntity ensureReturnCreditNote(OrderEntity order, ReturnResolutionEntity resolution,
                                                             String paymentReference, long amount) {
        var returnCase = returnCases.findById(resolution.getReturnCaseId())
                .orElseThrow(() -> new BusinessConflictException("Credit note is missing its return case."));
        if (!order.getId().equals(returnCase.getOrderId())
                || !order.getShopId().equals(returnCase.getShopId())) {
            throw new BusinessConflictException("Credit note return case belongs to a different order.");
        }
        List<ReturnResolutionItemEntity> allocations = resolutionItems.findByResolutionId(resolution.getId());
        if (allocations.isEmpty()) throw new BusinessConflictException("Credit note resolution has no return lines.");

        AccountingDocumentEntity source = documents
                .findFirstByOrderIdAndDocumentTypeOrderByIssuedAtDesc(order.getId(), AccountingDocumentType.PAYMENT_RECEIPT)
                .orElseGet(() -> documents
                        .findFirstByOrderIdAndDocumentTypeOrderByIssuedAtDesc(order.getId(), AccountingDocumentType.ORDER_CONFIRMATION)
                        .orElseGet(() -> ensure(order, AccountingDocumentType.ORDER_CONFIRMATION, null)));
        List<AccountingDocumentLineEntity> sourceLines = lines.findByDocumentIdOrderByIdAsc(source.getId());
        Map<UUID, AccountingDocumentLineEntity> sourceLineByOrderItem = sourceLines.stream()
                .filter(line -> line.getOrderItemId() != null)
                .collect(Collectors.toMap(AccountingDocumentLineEntity::getOrderItemId, line -> line));
        List<AccountingDocumentSellerEntity> sourceSellers = sellers.findByDocumentIdOrderByIdAsc(source.getId());
        Map<UUID, AccountingDocumentSellerEntity> sourceSellerById = new HashMap<>();
        for (AccountingDocumentSellerEntity seller : sourceSellers) sourceSellerById.put(seller.getId(), seller);
        Map<UUID, String> sellerSnapshotBySourceSeller = new HashMap<>();
        Map<UUID, Integer> allocatedQuantityByOrderItem = new HashMap<>();
        List<OrderItemEntity> orderRows = orderItems.findByOrderIdOrderByIdAsc(order.getId());
        Map<UUID, OrderItemEntity> orderItemById = orderRows.stream()
                .collect(Collectors.toMap(OrderItemEntity::getId, line -> line));
        record PendingLine(ReturnResolutionItemEntity allocation, OrderItemEntity orderItem,
                           AccountingDocumentLineEntity original, UUID sourceSellerId,
                           long lineTotal, long productDiscount, long voucherDiscount) { }
        List<PendingLine> pendingLines = new java.util.ArrayList<>();

        AccountingDocumentEntity document = new AccountingDocumentEntity();
        document.setOrderId(order.getId());
        document.setDocumentType(AccountingDocumentType.CREDIT_NOTE);
        document.setDocumentNumber(prefix(AccountingDocumentType.CREDIT_NOTE) + order.getOrderNumber() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        document.setSourceQuality(source.getSourceQuality());
        document.setBuyerSnapshot(source.getBuyerSnapshot());
        document.setCurrency(source.getCurrency());
        document.setPaymentMethod(source.getPaymentMethod());
        document.setPaymentReference(paymentReference);
        document.setIssuedAt(Instant.now());
        document.setShippingFee(0);
        document.setShippingFeeBeforeDiscount(0);
        document.setShippingDiscountAmount(0);

        long subtotal = 0;
        long productDiscount = 0;
        long voucherDiscount = 0;
        long allocatedRefund = 0;
        for (ReturnResolutionItemEntity allocation : allocations) {
            ReturnCaseItemEntity caseItem = returnCaseItems.findById(allocation.getReturnCaseItemId())
                    .orElseThrow(() -> new BusinessConflictException("Credit note is missing a return item."));
            OrderItemEntity orderItem = orderItemById.get(caseItem.getOrderItemId());
            AccountingDocumentLineEntity original = sourceLineByOrderItem.get(caseItem.getOrderItemId());
            if (orderItem == null || original == null || allocation.getQuantity() <= 0
                    || allocation.getQuantity() > original.getQuantity()) {
                throw new BusinessConflictException("Credit note return line does not match its original invoice.");
            }

            UUID sourceSellerId = original.getSellerId();
            if (!sellerSnapshotBySourceSeller.containsKey(sourceSellerId)) {
                AccountingDocumentSellerEntity sourceSeller = sourceSellerById.get(sourceSellerId);
                if (sourceSeller == null) {
                    throw new BusinessConflictException("Credit note cannot find the original seller snapshot.");
                }
                sellerSnapshotBySourceSeller.put(sourceSellerId, sourceSeller.getSellerSnapshot());
            }

            int beforeQuantity = allocatedQuantityByOrderItem.getOrDefault(
                    orderItem.getId(), orderItem.getReturnResolvedQuantity());
            int afterQuantity = Math.addExact(beforeQuantity, allocation.getQuantity());
            allocatedQuantityByOrderItem.put(orderItem.getId(), afterQuantity);
            long lineTotal = ReturnRefundAllocation.cumulativeDelta(original.getLineTotal(), beforeQuantity,
                    afterQuantity, original.getQuantity());
            long lineProductDiscount = ReturnRefundAllocation.cumulativeDelta(original.getProductDiscount(), beforeQuantity,
                    afterQuantity, original.getQuantity());
            long lineVoucherDiscount = ReturnRefundAllocation.cumulativeDelta(original.getVoucherDiscount(), beforeQuantity,
                    afterQuantity, original.getQuantity());
            long netRefund = Math.subtractExact(lineTotal, lineVoucherDiscount);
            if (netRefund < 0 || netRefund != allocation.getAmount()) {
                throw new BusinessConflictException("Credit note amount does not match the issued order line allocation.");
            }

            pendingLines.add(new PendingLine(allocation, orderItem, original, sourceSellerId,
                    lineTotal, lineProductDiscount, lineVoucherDiscount));

            subtotal = Math.addExact(subtotal, Math.multiplyExact(original.getUnitPrice(), allocation.getQuantity()));
            productDiscount = Math.addExact(productDiscount, lineProductDiscount);
            voucherDiscount = Math.addExact(voucherDiscount, lineVoucherDiscount);
            allocatedRefund = Math.addExact(allocatedRefund, allocation.getAmount());
        }
        if (amount != allocatedRefund) {
            throw new BusinessConflictException("Credit note total must equal the return resolution lines.");
        }
        document.setSubtotal(subtotal);
        document.setProductDiscount(productDiscount);
        document.setVoucherDiscount(voucherDiscount);
        document.setTotalAmount(allocatedRefund);
        document = documents.saveAndFlush(document);

        Map<UUID, UUID> creditSellerBySourceSeller = new HashMap<>();
        for (var sellerSnapshot : sellerSnapshotBySourceSeller.entrySet()) {
            AccountingDocumentSellerEntity seller = new AccountingDocumentSellerEntity();
            seller.setDocumentId(document.getId());
            seller.setSellerSnapshot(sellerSnapshot.getValue());
            seller = sellers.save(seller);
            creditSellerBySourceSeller.put(sellerSnapshot.getKey(), seller.getId());
        }
        for (PendingLine pending : pendingLines) {
            AccountingDocumentLineEntity line = new AccountingDocumentLineEntity();
            line.setDocumentId(document.getId());
            line.setSellerId(creditSellerBySourceSeller.get(pending.sourceSellerId()));
            line.setOrderItemId(pending.orderItem().getId());
            line.setProductId(pending.original().getProductId());
            line.setProductName(pending.original().getProductName());
            line.setQuantity(pending.allocation().getQuantity());
            line.setUnitPrice(pending.original().getUnitPrice());
            line.setProductDiscount(pending.productDiscount());
            line.setVoucherDiscount(pending.voucherDiscount());
            line.setLineTotal(pending.lineTotal());
            lines.save(line);
        }
        return document;
    }

    @Transactional(readOnly = true)
    public AccountingDocumentEntity latest(UUID orderId) {
        return documents.findByOrderIdOrderByIssuedAtDesc(orderId).stream().findFirst().orElseThrow();
    }

    @Transactional
    public DocumentView invoice(UUID orderId, boolean paid) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        AccountingDocumentType type = paid
                ? AccountingDocumentType.PAYMENT_RECEIPT : AccountingDocumentType.ORDER_CONFIRMATION;
        if (paid) {
            long collected = order.getTotalAmount();
            if ("COD".equalsIgnoreCase(order.getPaymentMethod())) {
                collected = payments.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                        .filter(payment -> "COD".equalsIgnoreCase(payment.getProvider()))
                        .findFirst().map(payment -> payment.getCollectedAmount()).orElse(0L);
            }
            ensure(order, type, null, collected);
        }
        AccountingDocumentEntity document = documents
                .findFirstByOrderIdAndDocumentTypeOrderByIssuedAtDesc(orderId, type).orElseThrow();
        return new DocumentView(document,
                sellers.findByDocumentIdOrderByIdAsc(document.getId()),
                lines.findByDocumentIdOrderByIdAsc(document.getId()));
    }

    public record DocumentView(AccountingDocumentEntity document,
                               List<AccountingDocumentSellerEntity> sellers,
                               List<AccountingDocumentLineEntity> lines) {}

    private String prefix(AccountingDocumentType type) {
        return switch (type) {
            case ORDER_CONFIRMATION -> "OC-";
            case PAYMENT_RECEIPT -> "PR-";
            case CREDIT_NOTE -> "CN-";
        };
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize accounting snapshot", exception); }
    }

    private Object safe(Object value) { return value == null ? "" : value; }
}
