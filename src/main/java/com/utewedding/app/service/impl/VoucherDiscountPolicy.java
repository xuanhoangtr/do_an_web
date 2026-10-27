package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.VoucherUsageSlot;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.domain.entity.VoucherUsageEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.VoucherRepository;
import com.utewedding.app.repository.VoucherUsageRepository;
import com.utewedding.app.service.port.DiscountPolicy;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public void lockForCheckout(java.util.Collection<String> codes) {
        if (codes == null) return;
        codes.stream().filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct().sorted()
                .forEach(voucherRepository::findByCodeForUpdate);
    }

    @Override
    @Transactional
    public Map<String, String> validateBatchUsage(UUID userId, Map<String, Integer> requestedUses,
                                                  boolean lock) {
        if (requestedUses == null || requestedUses.isEmpty()) return Map.of();
        Map<String, String> errors = new LinkedHashMap<>();
        requestedUses.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> Map.entry(normalize(entry.getKey()), entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String code = entry.getKey();
                    int requested = entry.getValue();
                    VoucherEntity voucher = (lock ? voucherRepository.findByCodeForUpdate(code)
                            : voucherRepository.findByCodeIgnoreCase(code)).orElse(null);
                    if (voucher == null) {
                        errors.put(code, "Voucher không còn khả dụng.");
                        return;
                    }
                    if (voucher.getTotalQuota() != null
                            && (long) voucher.getUsedCount() + requested > voucher.getTotalQuota()) {
                        errors.put(code, "Voucher không còn đủ lượt sử dụng cho các cửa hàng đã chọn.");
                        return;
                    }
                    if (userId != null && voucher.getPerUserQuota() != null) {
                        long priorUserUses = usageRepository.countByVoucherIdAndUserId(voucher.getId(), userId);
                        if (priorUserUses >= voucher.getPerUserQuota()
                                || requested > voucher.getPerUserQuota() - priorUserUses) {
                            errors.put(code, "Bạn không còn đủ lượt voucher này cho tất cả cửa hàng đã chọn.");
                        }
                    }
                });
        return Map.copyOf(errors);
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
        return calculateLocked(voucher, code, eligibleAmount, 0, null, ShopDefaults.PLATFORM_SHOP_ID);
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
        if (discountCode == null || discountCode.isBlank() || eligibleAmount < 0) return DiscountResult.none();
        return evaluate(discountCode, eligibleAmount, customerShippingFee, shippingProviderCode, userId,
                ShopDefaults.PLATFORM_SHOP_ID, lock);
    }

    @Override
    @Transactional
    public CheckoutDiscounts calculateCheckoutSlots(String merchandiseCode, String shippingCode,
                                                     long eligibleAmount, long customerShippingFee,
                                                     String shippingProviderCode, UUID userId) {
        return calculateSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, ShopDefaults.PLATFORM_SHOP_ID, true);
    }

    @Override
    @Transactional
    public CheckoutDiscounts calculateCheckoutSlots(String merchandiseCode, String shippingCode,
                                                     long eligibleAmount, long customerShippingFee,
                                                     String shippingProviderCode, UUID userId, UUID selectedShopId) {
        return calculateSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, selectedShopId, true);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutDiscounts previewCheckoutSlots(String merchandiseCode, String shippingCode,
                                                   long eligibleAmount, long customerShippingFee,
                                                   String shippingProviderCode, UUID userId) {
        return calculateSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, ShopDefaults.PLATFORM_SHOP_ID, false);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutDiscounts previewCheckoutSlots(String merchandiseCode, String shippingCode,
                                                   long eligibleAmount, long customerShippingFee,
                                                   String shippingProviderCode, UUID userId, UUID selectedShopId) {
        return calculateSlots(merchandiseCode, shippingCode, eligibleAmount, customerShippingFee,
                shippingProviderCode, userId, selectedShopId, false);
    }

    private CheckoutDiscounts calculateSlots(String merchandiseCode, String shippingCode,
                                             long eligibleAmount, long customerShippingFee,
                                             String shippingProviderCode, UUID userId, UUID selectedShopId,
                                             boolean lock) {
        String merchandise = normalize(merchandiseCode);
        String shipping = normalize(shippingCode);
        if (merchandise != null && shipping != null && merchandise.equals(shipping)) {
            throw new BusinessConflictException("Một voucher không thể dùng cho cả tiền hàng và phí vận chuyển.");
        }
        List<String> codes = new ArrayList<>();
        if (merchandise != null) codes.add(merchandise);
        if (shipping != null) codes.add(shipping);
        codes.sort(Comparator.naturalOrder());
        Map<String, DiscountResult> results = new LinkedHashMap<>();
        for (String code : codes) {
            results.put(code, evaluate(code, eligibleAmount, customerShippingFee,
                    shippingProviderCode, userId, selectedShopId, lock));
        }
        DiscountResult merchandiseResult = merchandise == null
                ? DiscountResult.none() : results.get(merchandise);
        DiscountResult shippingResult = shipping == null
                ? DiscountResult.none() : results.get(shipping);

        // A request produced by an older client uses only discountCode and may put
        // either voucher type there. Preserve that contract while the new form is strict.
        boolean legacySingleCode = shipping == null && merchandise != null;
        if (legacySingleCode && merchandiseResult.type() != null && merchandiseResult.type().isShippingVoucher()) {
            shippingResult = merchandiseResult;
            merchandiseResult = DiscountResult.none();
        } else {
            requireSlotType(merchandiseResult, VoucherUsageSlot.MERCHANDISE);
            requireSlotType(shippingResult, VoucherUsageSlot.SHIPPING);
        }
        return new CheckoutDiscounts(merchandiseResult, shippingResult);
    }

    private DiscountResult evaluate(String rawCode, long eligibleAmount, long customerShippingFee,
                                    String shippingProviderCode, UUID userId, UUID selectedShopId,
                                    boolean lock) {
        if (rawCode == null || rawCode.isBlank() || eligibleAmount < 0) return DiscountResult.none();
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        VoucherEntity voucher = (lock ? voucherRepository.findByCodeForUpdate(code)
                : voucherRepository.findByCodeIgnoreCase(code)).orElse(null);
        if (voucher == null) throw new BusinessConflictException("Voucher không tồn tại.");
        if (userId != null && voucher.getPerUserQuota() != null
                && usageRepository.countByVoucherIdAndUserId(voucher.getId(), userId) >= voucher.getPerUserQuota()) {
            throw new BusinessConflictException("Bạn đã dùng hết lượt voucher này.");
        }
        return calculateLocked(voucher, code, eligibleAmount, customerShippingFee,
                shippingProviderCode, selectedShopId);
    }

    private void requireSlotType(DiscountResult result, VoucherUsageSlot slot) {
        if (result.type() == null) return;
        boolean shipping = result.type().isShippingVoucher();
        if ((slot == VoucherUsageSlot.SHIPPING) != shipping) {
            throw new BusinessConflictException(slot == VoucherUsageSlot.SHIPPING
                    ? "Ô mã vận chuyển chỉ nhận voucher giảm phí vận chuyển."
                    : "Ô mã tiền hàng chỉ nhận voucher giảm tiền hàng.");
        }
    }

    private String normalize(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private DiscountResult calculateLocked(VoucherEntity voucher, String code, long eligibleAmount,
                                           long customerShippingFee, String shippingProviderCode,
                                           UUID selectedShopId) {
        validateScope(voucher, selectedShopId);
        Instant now = Instant.now();
        if (!voucher.isActive() || now.isBefore(voucher.getStartsAt()) || !now.isBefore(voucher.getEndsAt()))
            throw new BusinessConflictException("Voucher đã hết hạn hoặc chưa có hiệu lực.");
        boolean shippingVoucher = voucher.getType().isShippingVoucher();
        if (shippingVoucher && voucher.getScope() != VoucherScope.PLATFORM) {
            throw new BusinessConflictException("Voucher vận chuyển của cửa hàng chưa được hỗ trợ.");
        }
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
        return new DiscountResult(amount, code, voucher.getType(), voucher.getId(),
                voucher.getScope(), voucher.getFundingSource());
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
        reserve(discountCode, userId, orderId, discountAmount, ShopDefaults.PLATFORM_SHOP_ID);
    }

    @Override
    @Transactional
    public void reserve(String discountCode, UUID userId, UUID orderId, long discountAmount,
                        UUID selectedShopId) {
        if (discountCode == null || discountCode.isBlank()) return;
        String code = discountCode.trim().toUpperCase(Locale.ROOT);
        VoucherEntity voucher = voucherRepository.findByCodeForUpdate(code).orElseThrow(
                () -> new BusinessConflictException("Voucher không tồn tại."));
        validateScope(voucher, selectedShopId);
        VoucherUsageSlot slot = voucher.getType().isShippingVoucher()
                ? VoucherUsageSlot.SHIPPING : VoucherUsageSlot.MERCHANDISE;
        VoucherUsageEntity existing = usageRepository.findByOrderIdAndSlotForUpdate(orderId, slot).orElse(null);
        if (existing != null) {
            if (existing.getVoucherId().equals(voucher.getId())
                    && existing.getUserId().equals(userId)
                    && existing.getDiscountAmount() == discountAmount) return;
            throw new BusinessConflictException("Đơn hàng đã giữ một voucher khác cho mục này.");
        }
        if (usageRepository.existsByOrderIdAndVoucherId(orderId, voucher.getId())) {
            throw new BusinessConflictException("Một voucher không thể được giữ hai lần cho cùng đơn hàng.");
        }
        if (voucher.getTotalQuota() != null && voucher.getUsedCount() >= voucher.getTotalQuota())
            throw new BusinessConflictException("Voucher đã hết lượt sử dụng.");
        if (voucher.getPerUserQuota() != null && usageRepository.countByVoucherIdAndUserId(voucher.getId(), userId) >= voucher.getPerUserQuota())
            throw new BusinessConflictException("Bạn đã dùng hết lượt voucher này.");
        VoucherUsageEntity usage = new VoucherUsageEntity();
        usage.setVoucherId(voucher.getId()); usage.setUserId(userId); usage.setOrderId(orderId); usage.setDiscountAmount(discountAmount);
        usage.setSlot(slot);
        usageRepository.save(usage); voucher.setUsedCount(Math.addExact(voucher.getUsedCount(), 1)); voucherRepository.save(voucher);
    }

    private void validateScope(VoucherEntity voucher, UUID selectedShopId) {
        if (voucher.getScope() == VoucherScope.PLATFORM) {
            if (voucher.getFundingSource() != VoucherFundingSource.PLATFORM || voucher.getShopId() != null) {
                throw new BusinessConflictException("Thông tin phạm vi hoặc nguồn tài trợ voucher không hợp lệ.");
            }
            return;
        }
        if (voucher.getScope() != VoucherScope.SHOP || voucher.getFundingSource() != VoucherFundingSource.SHOP
                || voucher.getShopId() == null || selectedShopId == null
                || !voucher.getShopId().equals(selectedShopId)) {
            throw new BusinessConflictException("Voucher cửa hàng chỉ áp dụng trong giỏ hàng của cửa hàng đó.");
        }
        if (voucher.getType() == null || voucher.getType().isShippingVoucher()) {
            throw new BusinessConflictException("Voucher cửa hàng chỉ áp dụng giảm tiền hàng.");
        }
    }

    @Override
    @Transactional
    public void release(UUID orderId) {
        if (orderId == null) return;
        // Reserve locks vouchers in code order before usage-slot rows. Release uses
        // that same order so two-slot cancellation cannot invert the voucher locks.
        java.util.List<VoucherUsageEntity> observed = usageRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId);
        Map<UUID, VoucherEntity> lockedVouchers = new LinkedHashMap<>();
        Map<UUID, String> voucherCodes = new LinkedHashMap<>();
        for (VoucherUsageEntity usage : observed) {
            voucherRepository.findById(usage.getVoucherId())
                    .map(VoucherEntity::getCode)
                    .ifPresent(code -> voucherCodes.put(usage.getVoucherId(), normalize(code)));
        }
        voucherCodes.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    VoucherEntity voucher = voucherRepository.findByCodeForUpdate(entry.getValue()).orElse(null);
                    if (voucher != null && !voucher.getId().equals(entry.getKey())) {
                        throw new BusinessConflictException("Mã voucher đã thay đổi trong lúc giải phóng lượt.");
                    }
                    lockedVouchers.put(entry.getKey(), voucher);
                });
        java.util.List<VoucherUsageEntity> usages = usageRepository.findAllByOrderIdForUpdate(orderId);
        for (VoucherUsageEntity usage : usages) {
            VoucherEntity voucher = lockedVouchers.get(usage.getVoucherId());
            usageRepository.delete(usage);
            if (voucher != null && voucher.getUsedCount() > 0) {
                voucher.setUsedCount(voucher.getUsedCount() - 1);
                voucherRepository.save(voucher);
            }
        }
    }

}
