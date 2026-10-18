package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.ShopCommissionPolicyEntity;
import com.utewedding.app.domain.entity.ShopOrderFinancialSnapshotEntity;
import com.utewedding.app.domain.entity.ShopSettlementEntryEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ShopCommissionPolicyRepository;
import com.utewedding.app.repository.ShopFinanceStateRepository;
import com.utewedding.app.repository.ShopOrderFinancialSnapshotRepository;
import com.utewedding.app.repository.ShopSettlementEntryRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.finance.ShopCommissionPolicyCommand;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.DiscountPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable checkout commission snapshots and the first append-only vendor ledger entry. */
@Service
public class ShopFinancialService {
    private static final BigInteger BPS = BigInteger.valueOf(10_000);

    private final ShopFinanceStateRepository financeStates;
    private final ShopCommissionPolicyRepository policies;
    private final ShopOrderFinancialSnapshotRepository snapshots;
    private final ShopSettlementEntryRepository entries;
    private final UserRepository users;
    private final AuditLogPort audit;

    @PersistenceContext
    private EntityManager entityManager;

    public ShopFinancialService(ShopFinanceStateRepository financeStates,
                                ShopCommissionPolicyRepository policies,
                                ShopOrderFinancialSnapshotRepository snapshots,
                                ShopSettlementEntryRepository entries,
                                UserRepository users,
                                AuditLogPort audit) {
        this.financeStates = financeStates;
        this.policies = policies;
        this.snapshots = snapshots;
        this.entries = entries;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopOrderFinancialSnapshotEntity snapshotAtCheckout(OrderEntity order, List<OrderItemEntity> items,
                                                                 DiscountPolicy.DiscountResult merchandiseDiscount) {
        if (order == null || order.getId() == null || order.getShopId() == null) {
            throw new BusinessConflictException("Không thể tạo ảnh chụp tài chính cho đơn hàng.");
        }
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) return null;
        lockFinanceState(order.getShopId());
        var existing = snapshots.findByOrderId(order.getId());
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().getShopId(), order.getShopId())) {
                throw new BusinessConflictException("Ảnh chụp tài chính của đơn không khớp cửa hàng.");
            }
            return existing.get();
        }

        long merchandiseGross = sumMerchandise(items);
        long shopFundedDiscount = merchandiseDiscount != null
                && merchandiseDiscount.fundingSource() == VoucherFundingSource.SHOP
                ? Math.min(order.getVoucherDiscountAmount(), merchandiseGross) : 0;
        long commissionBase = merchandiseGross - shopFundedDiscount;
        Instant checkoutAt = order.getCreatedAt() == null ? Instant.now() : order.getCreatedAt();
        ShopCommissionPolicyEntity policy = policies
                .findTopByEffectiveAtLessThanEqualOrderByEffectiveAtDescPolicyVersionDesc(checkoutAt)
                .orElseThrow(() -> new BusinessConflictException("Chưa cấu hình chính sách hoa hồng cho đơn hàng."));
        long commission = commission(commissionBase, policy.getRateBps());
        long payable = commissionBase - commission;
        return snapshots.saveAndFlush(new ShopOrderFinancialSnapshotEntity(order.getId(), order.getShopId(),
                merchandiseGross, shopFundedDiscount, commissionBase, policy.getRateBps(), commission,
                payable, policy.getPolicyVersion(), checkoutAt));
    }

    /** Creates one net merchandise SALE credit after canonical payment capture. */
    @Transactional(rollbackFor = Exception.class)
    public ShopSettlementEntryEntity recordSale(OrderEntity order) {
        if (order == null || order.getId() == null || order.getShopId() == null
                || ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) return null;
        lockFinanceState(order.getShopId());
        ShopOrderFinancialSnapshotEntity snapshot = snapshots.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessConflictException(
                        "Thiếu ảnh chụp hoa hồng của đơn; chưa thể ghi sổ doanh thu cửa hàng."));
        if (!Objects.equals(snapshot.getShopId(), order.getShopId())) {
            throw new BusinessConflictException("Ảnh chụp hoa hồng không khớp cửa hàng của đơn.");
        }
        var existing = entries.findFirstByShopIdAndOrderIdAndEntryType(
                order.getShopId(), order.getId(), ShopSettlementEntryEntity.Type.SALE);
        if (existing.isPresent()) {
            ShopSettlementEntryEntity saved = existing.get();
            if (saved.getAmount() != snapshot.getVendorPayable()
                    || saved.getDirection() != ShopSettlementEntryEntity.Direction.CREDIT
                    || !Objects.equals(saved.getSourceIdentity(), "sale:" + order.getId())) {
                throw new BusinessConflictException("Bút toán SALE hiện có không khớp ảnh chụp hoa hồng.");
            }
            return saved;
        }
        return entries.saveAndFlush(new ShopSettlementEntryEntity(order.getShopId(), order.getId(),
                ShopSettlementEntryEntity.Type.SALE, ShopSettlementEntryEntity.Direction.CREDIT,
                snapshot.getVendorPayable(), "sale:" + order.getId(), order.getOrderNumber(), Instant.now()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopCommissionPolicyEntity setGlobalCommissionRate(UUID actorId, ShopCommissionPolicyCommand command,
                                                               RequestContext context) {
        UserEntity actor = users.findById(actorId).orElseThrow();
        if (actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Admin hoặc Super Admin được cấu hình hoa hồng nền tảng.");
        }
        if (command == null || command.rateBps() < 0 || command.rateBps() > 10_000) {
            throw new BusinessConflictException("Tỷ lệ hoa hồng phải nằm trong khoảng 0–10000 điểm cơ bản.");
        }
        Instant now = Instant.now();
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        if (effectiveAt.isBefore(now)) {
            throw new BusinessConflictException("Chính sách hoa hồng mới không được áp dụng hồi tố.");
        }
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", "shop-commission-global-policy").getSingleResult();
        ShopCommissionPolicyEntity latest = policies.findTopByOrderByPolicyVersionDesc().orElseThrow();
        if (!effectiveAt.isAfter(latest.getEffectiveAt())) {
            throw new BusinessConflictException("Thời điểm hiệu lực phải sau chính sách đã lên lịch gần nhất.");
        }
        long nextVersion = Math.addExact(latest.getPolicyVersion(), 1);
        ShopCommissionPolicyEntity saved = policies.saveAndFlush(
                new ShopCommissionPolicyEntity(nextVersion, command.rateBps(), effectiveAt, actorId));
        audit.record("SHOP_COMMISSION_POLICY_CREATED", actorId, "ShopCommissionPolicy", null,
                Map.of("policyVersion", nextVersion, "rateBps", command.rateBps(), "effectiveAt", effectiveAt),
                context);
        return saved;
    }

    private void lockFinanceState(UUID shopId) {
        if (financeStates.findByShopIdForUpdate(shopId).isEmpty()) {
            throw new BusinessConflictException("Chưa khởi tạo sổ tài chính cho cửa hàng.");
        }
    }

    private static long sumMerchandise(List<OrderItemEntity> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessConflictException("Đơn hàng chưa có sản phẩm để tính công nợ cửa hàng.");
        }
        long total = 0;
        try {
            for (OrderItemEntity item : items) {
                if (item == null || item.getLineTotal() < 0) {
                    throw new BusinessConflictException("Giá trị sản phẩm trong đơn không hợp lệ.");
                }
                total = Math.addExact(total, item.getLineTotal());
            }
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Tổng tiền hàng vượt giới hạn ghi nhận.");
        }
        return total;
    }

    private static long commission(long base, int rateBps) {
        try {
            return BigInteger.valueOf(base).multiply(BigInteger.valueOf(rateBps)).divide(BPS).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Hoa hồng vượt giới hạn ghi nhận.");
        }
    }
}
