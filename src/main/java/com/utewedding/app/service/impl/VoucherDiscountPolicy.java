package com.utewedding.app.service.impl;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.domain.entity.VoucherUsageEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.VoucherRepository;
import com.utewedding.app.repository.VoucherUsageRepository;
import com.utewedding.app.service.port.DiscountPolicy;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional database-backed voucher policy. */
@Service
public class VoucherDiscountPolicy implements DiscountPolicy {
    private static final BigInteger PERCENT_BASE = BigInteger.valueOf(100);

    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository usageRepository;

    public VoucherDiscountPolicy(VoucherRepository voucherRepository, VoucherUsageRepository usageRepository) {
        this.voucherRepository = voucherRepository;
        this.usageRepository = usageRepository;
    }

    @Override
    @Transactional
    public DiscountResult calculate(String discountCode, long eligibleAmount) {
        if (discountCode == null || discountCode.isBlank() || eligibleAmount < 0) {
            return DiscountResult.none();
        }
        String code = discountCode.trim().toUpperCase(Locale.ROOT);
        VoucherEntity voucher = voucherRepository.findByCodeForUpdate(code).orElse(null);
        if (voucher == null) throw new BusinessConflictException("Voucher không tồn tại.");
        return calculateLocked(voucher, code, eligibleAmount, 0, null);
    }

    @Override
    @Transactional
    public DiscountResult calculateForUser(String discountCode, long eligibleAmount, UUID userId) {
        return calculateForCheckout(discountCode, eligibleAmount, 0, null, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public DiscountResult previewForUser(String discountCode, long eligibleAmount, UUID userId) {
        return previewForCheckout(discountCode, eligibleAmount, 0, null, userId);
    }

    @Override
    @Transactional
    public DiscountResult calculateForCheckout(String discountCode, long eligibleAmount,
                                               long customerShippingFee, String shippingProviderCode,
                                               UUID userId) {
        return calculateForCheckout(discountCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, true);
    }

    @Override
    @Transactional(readOnly = true)
    public DiscountResult previewForCheckout(String discountCode, long eligibleAmount,
                                             long customerShippingFee, String shippingProviderCode,
                                             UUID userId) {
        return calculateForCheckout(discountCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, false);
    }

    private DiscountResult calculateForCheckout(String discountCode, long eligibleAmount,
                                                long customerShippingFee, String shippingProviderCode,
                                                UUID userId, boolean lock) {
        if (discountCode == null || discountCode.isBlank() || eligibleAmount < 0) {
            return DiscountResult.none();
        }
        String code = discountCode.trim().toUpperCase(Locale.ROOT);
        VoucherEntity voucher = (lock ? voucherRepository.findByCodeForUpdate(code)
                : voucherRepository.findByCodeIgnoreCase(code)).orElse(null);
        if (voucher == null) throw new BusinessConflictException("Voucher không tồn tại.");
        if (userId != null && voucher.getPerUserQuota() != null
                && usageRepository.countByVoucherIdAndUserId(voucher.getId(), userId) >= voucher.getPerUserQuota()) {
            throw new BusinessConflictException("Bạn đã dùng hết lượt voucher này.");
        }
        return calculateLocked(voucher, code, eligibleAmount, customerShippingFee, shippingProviderCode);
    }

    private DiscountResult calculateLocked(VoucherEntity voucher, String code, long eligibleAmount,
                                           long customerShippingFee, String shippingProviderCode) {
        Instant now = Instant.now();
        if (!voucher.isActive() || now.isBefore(voucher.getStartsAt()) || !now.isBefore(voucher.getEndsAt()))
            throw new BusinessConflictException("Voucher đã hết hạn hoặc chưa có hiệu lực.");
        boolean shippingVoucher = voucher.getType().isShippingVoucher();
        if (shippingVoucher && (shippingProviderCode == null || !"GHN".equalsIgnoreCase(shippingProviderCode))) {
            if ("INTERNAL".equalsIgnoreCase(shippingProviderCode) || customerShippingFee == 0)
                throw new BusinessConflictException("Đơn này đã được miễn phí vận chuyển.");
            throw new BusinessConflictException("Voucher vận chuyển chỉ áp dụng cho đơn ngoại tỉnh giao qua GHN.");
        }
        if (shippingVoucher && customerShippingFee <= 0)
            throw new BusinessConflictException("Đơn này đã được miễn phí vận chuyển.");
        if (eligibleAmount < voucher.getMinimumOrderAmount())
            throw new BusinessConflictException("Đơn hàng chưa đạt giá trị tối thiểu của voucher.");
        if (voucher.getTotalQuota() != null && voucher.getUsedCount() >= voucher.getTotalQuota())
            throw new BusinessConflictException("Voucher đã hết lượt sử dụng.");
        long amount = switch (voucher.getType()) {
            case PERCENT -> calculatePercentDiscount(eligibleAmount, voucher.getValueAmount());
            case FIXED -> voucher.getValueAmount();
            case SHIPPING_FIXED -> Math.min(voucher.getValueAmount(), customerShippingFee);
            case SHIPPING_FREE -> customerShippingFee;
        };
        if (voucher.getType() == VoucherType.SHIPPING_FREE
                && voucher.getMaximumDiscountAmount() != null
                && voucher.getMaximumDiscountAmount() < customerShippingFee) {
            throw new BusinessConflictException("Mã miễn phí vận chuyển có giới hạn thấp hơn phí giao hàng.");
        }
        if (voucher.getType() != VoucherType.SHIPPING_FREE && voucher.getMaximumDiscountAmount() != null)
            amount = Math.min(amount, voucher.getMaximumDiscountAmount());
        amount = Math.min(amount, shippingVoucher ? customerShippingFee : eligibleAmount);
        return new DiscountResult(amount, code, voucher.getType());
    }

    private long calculatePercentDiscount(long eligibleAmount, long percent) {
        try {
            return BigInteger.valueOf(eligibleAmount)
                    .multiply(BigInteger.valueOf(percent))
                    .divide(PERCENT_BASE)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new BusinessConflictException(
                    "Giá trị đơn hàng hoặc voucher vượt quá giới hạn tính toán.");
        }
    }

    @Override
    @Transactional
    public void reserve(String discountCode, UUID userId, UUID orderId, long discountAmount) {
        if (discountCode == null || discountCode.isBlank()) return;
        String code = discountCode.trim().toUpperCase(Locale.ROOT);
        VoucherEntity voucher = voucherRepository.findByCodeForUpdate(code).orElseThrow(
                () -> new BusinessConflictException("Voucher không tồn tại."));
        if (voucher.getTotalQuota() != null && voucher.getUsedCount() >= voucher.getTotalQuota())
            throw new BusinessConflictException("Voucher đã hết lượt sử dụng.");
        if (voucher.getPerUserQuota() != null && usageRepository.countByVoucherIdAndUserId(voucher.getId(), userId) >= voucher.getPerUserQuota())
            throw new BusinessConflictException("Bạn đã dùng hết lượt voucher này.");
        VoucherUsageEntity usage = new VoucherUsageEntity();
        usage.setVoucherId(voucher.getId()); usage.setUserId(userId); usage.setOrderId(orderId); usage.setDiscountAmount(discountAmount);
        usageRepository.save(usage); voucher.setUsedCount(Math.addExact(voucher.getUsedCount(), 1)); voucherRepository.save(voucher);
    }

    @Override
    @Transactional
    public void release(UUID orderId) {
        if (orderId == null) return;
        VoucherUsageEntity usage = usageRepository.findByOrderId(orderId).orElse(null);
        if (usage == null) return;
        VoucherEntity voucher = voucherRepository.findByIdForUpdate(usage.getVoucherId()).orElse(null);
        usageRepository.delete(usage);
        if (voucher != null && voucher.getUsedCount() > 0) {
            voucher.setUsedCount(voucher.getUsedCount() - 1);
            voucherRepository.save(voucher);
        }
    }

}
