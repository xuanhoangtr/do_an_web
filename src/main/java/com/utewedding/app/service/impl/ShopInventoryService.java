package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShopInventoryAdjustmentEntity;
import com.utewedding.app.dto.request.InventoryAdjustmentForm;
import com.utewedding.app.dto.response.VendorInventoryProductView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopInventoryAdjustmentRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopInventoryService {
    private static final int PAGE_SIZE = 20;
    private final VendorWorkspaceContext workspaces;
    private final ShopAccessPolicy accessPolicy;
    private final ProductRepository products;
    private final ShopInventoryAdjustmentRepository adjustments;
    private final TextSanitizer sanitizer;
    private final AuditLogPort audit;

    public ShopInventoryService(VendorWorkspaceContext workspaces, ShopAccessPolicy accessPolicy,
                                ProductRepository products, ShopInventoryAdjustmentRepository adjustments,
                                TextSanitizer sanitizer, AuditLogPort audit) {
        this.workspaces = workspaces;
        this.accessPolicy = accessPolicy;
        this.products = products;
        this.adjustments = adjustments;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<VendorInventoryProductView> list(UserPrincipal actor, int requestedPage) {
        UUID shopId = workspaces.requireWorkspace(actor).shopId();
        return products.findByShopIdOrderByUpdatedAtDescIdAsc(shopId,
                        PageRequest.of(Math.max(0, requestedPage), PAGE_SIZE, Sort.unsorted()))
                .map(this::view);
    }

    @Transactional(rollbackFor = Exception.class)
    public VendorInventoryProductView adjust(UserPrincipal actor, UUID productId,
                                              InventoryAdjustmentForm form) {
        if (form == null || form.getDeltaQuantity() == null || form.getVersion() == null) {
            throw new BusinessConflictException("Thông tin điều chỉnh tồn kho không hợp lệ.");
        }
        int delta = form.getDeltaQuantity();
        if (delta == 0 || Math.abs((long) delta) > 1_000_000L) {
            throw new BusinessConflictException("Mức điều chỉnh phải khác 0 và không vượt quá 1.000.000.");
        }
        String reason = sanitizer.trimToNull(form.getReason(), 500);
        if (reason == null) throw new BusinessConflictException("Vui lòng ghi lý do điều chỉnh.");

        UUID shopId = workspaces.requireWorkspace(actor).shopId();
        accessPolicy.requireMutation(actor, shopId);
        ProductEntity product = products.findByIdAndShopIdForUpdate(productId, shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm trong cửa hàng của bạn."));
        if (form.getVersion() != product.getVersion()) {
            throw new BusinessConflictException("Tồn kho đã thay đổi. Tải lại trang rồi thử lại.");
        }

        int before = product.getStockQuantity();
        long versionBefore = product.getVersion();
        final int after;
        try {
            after = Math.addExact(before, delta);
        } catch (ArithmeticException exception) {
            throw new BusinessConflictException("Số lượng tồn kho vượt giới hạn cho phép.");
        }
        if (after < 0) throw new BusinessConflictException("Không thể giảm tồn kho thấp hơn 0.");

        product.setStockQuantity(after);
        products.saveAndFlush(product);
        long versionAfter = product.getVersion();
        if (versionAfter <= versionBefore) {
            throw new IllegalStateException("Stock adjustment did not advance product version");
        }
        adjustments.save(new ShopInventoryAdjustmentEntity(shopId, productId, actor.getId(), delta,
                before, after, versionBefore, versionAfter, reason));
        audit.record("SHOP_INVENTORY_ADJUSTED", actor.getId(), "Product", productId,
                Map.of("shopId", shopId, "delta", delta, "before", before, "after", after,
                        "versionBefore", versionBefore, "versionAfter", versionAfter, "reason", reason), null);
        return view(product);
    }

    private VendorInventoryProductView view(ProductEntity product) {
        return new VendorInventoryProductView(product.getId(), product.getName(), product.getSku(),
                product.getStockQuantity(), product.getVersion());
    }
}
