package com.utewedding.app.service;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.CategoryEntity;
import com.utewedding.app.domain.entity.VoucherEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MarketplaceAdminService {
    Page<CategoryEntity> categories(Pageable pageable);
    List<CategoryEntity> categoryParentOptions();
    CategoryEntity createCategory(CategoryCommand command, UUID actorId, RequestContext context);
    CategoryEntity updateCategory(Long id, CategoryCommand command, UUID actorId, RequestContext context);
    void deactivateCategory(Long id, UUID actorId, RequestContext context);

    Page<VoucherEntity> vouchers(Pageable pageable);
    VoucherEntity createVoucher(VoucherCommand command, UUID actorId, RequestContext context);
    VoucherEntity updateVoucher(UUID id, VoucherCommand command, UUID actorId, RequestContext context);
    void deactivateVoucher(UUID id, UUID actorId, RequestContext context);


    record CategoryCommand(String name, String slug, Long parentId, String description,
                           String imageUrl, int displayOrder, Boolean active) { }

    record VoucherCommand(String code, VoucherType type, long valueAmount,
                          long minimumOrderAmount, Long maximumDiscountAmount,
                          Instant startsAt, Instant endsAt, Integer totalQuota,
                          Integer perUserQuota, Boolean active) { }

}
