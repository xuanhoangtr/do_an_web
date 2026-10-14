package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.request.UpdateCartItemRequest;
import com.utewedding.app.dto.response.CartItemResponse;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.ProductImageRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.CartService;
import com.utewedding.app.util.ProductOptions;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductRepository productRepository) {
        this(cartRepository, cartItemRepository, productRepository, null);
    }

    @Autowired
    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductRepository productRepository,
                           ProductImageRepository productImageRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(UUID userId) {
        return cartRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(() -> new CartResponse(null, List.of(), 0, 0, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        ProductEntity product = activeProduct(request.productId());
        String customizationText = ProductOptions.resolvePhotoSize(
                product.getCustomizationGuide(), request.customizationText());
        CartEntity cart = findOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId())
                .orElseGet(() -> {
                    CartItemEntity created = new CartItemEntity();
                    created.setCartId(cart.getId());
                    created.setUserId(userId);
                    created.setProductId(product.getId());
                    created.setUnitPrice(java.math.BigDecimal.valueOf(product.getUnitPrice()));
                    created.setQuantity(0);
                    return created;
                });
        if (customizationText != null && item.getQuantity() > 0
                && item.getCustomizationText() != null
                && !item.getCustomizationText().equals(customizationText)) {
            throw new BusinessConflictException("Giỏ hàng đã có sản phẩm với "
                    + item.getCustomizationText() + ". Hãy xóa sản phẩm đó trước khi chọn kích thước khác.");
        }
        int newQuantity = Math.addExact(item.getQuantity(), request.quantity());
        assertAvailable(product, newQuantity);
        if (newQuantity < product.getMinOrderQuantity()) {
            throw new BusinessConflictException("Số lượng tối thiểu để đặt sản phẩm này là "
                    + product.getMinOrderQuantity() + ".");
        }
        if (customizationText != null) item.setCustomizationText(customizationText);
        item.setQuantity(newQuantity);
        cartItemRepository.save(item);
        // The controller can redirect after this POST (PRG); the service returns
        // the canonical cart state and never relies on a client-side increment.
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse updateQuantity(UUID userId, UpdateCartItemRequest request) {
        CartEntity cart = findOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        if (request.quantity() == 0) {
            cartItemRepository.delete(item);
        } else {
            ProductEntity product = productRepository.findById(request.productId()).orElse(null);
            if (product != null && request.quantity() < product.getMinOrderQuantity()) {
                throw new BusinessConflictException("Số lượng tối thiểu để đặt sản phẩm này là "
                        + product.getMinOrderQuantity() + ".");
            }
            if (request.quantity() > item.getQuantity()) {
                if (product == null || !product.isActive()) {
                    throw new ResourceNotFoundException("Không tìm thấy sản phẩm.");
                }
                assertAvailable(product, request.quantity());
            }
            item.setQuantity(request.quantity());
            if (product != null) item.setUnitPrice(java.math.BigDecimal.valueOf(product.getUnitPrice()));
            cartItemRepository.save(item);
        }
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse removeItem(UUID userId, UUID productId) {
        CartEntity cart = findOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        cartItemRepository.delete(item);
        return toResponse(cart);
    }

    private CartEntity findOrCreateCart(UUID userId) {
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            CartEntity cart = new CartEntity();
            cart.setUserId(userId);
            return cartRepository.save(cart);
        });
    }

    private ProductEntity activeProduct(UUID productId) {
        return productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
    }

    private void assertAvailable(ProductEntity product, int quantity) {
        if (quantity < 1 || quantity > 99) {
            throw new BusinessConflictException("Số lượng phải nằm trong khoảng từ 1 đến 99.");
        }
        if (quantity > product.getStockQuantity()) {
            throw new BusinessConflictException("Sản phẩm không đủ tồn kho.");
        }
    }

    private CartResponse toResponse(CartEntity cart) {
        long subtotal = 0;
        long discount = 0;
        List<CartItemResponse> items = new java.util.ArrayList<>();
        List<CartItemEntity> cartItems = cartItemRepository.findByCartIdOrderByIdAsc(cart.getId());
        Map<UUID, ProductEntity> products = cartItems.stream()
                .map(CartItemEntity::getProductId)
                .distinct()
                .collect(Collectors.collectingAndThen(Collectors.toList(), ids ->
                        productRepository.findAllById(ids).stream().collect(Collectors.toMap(
                                 ProductEntity::getId, Function.identity()))));
        Map<UUID, String> images = new HashMap<>();
        if (productImageRepository != null && !products.isEmpty()) {
            productImageRepository.findByProductIdInOrderByProductIdAscDisplayOrderAscCreatedAtAsc(products.keySet())
                    .forEach(image -> images.putIfAbsent(image.getProductId(), image.getImageUrl()));
        }
        for (CartItemEntity item : cartItems) {
            // A product can be deactivated after it was added to a cart. Keep the
            // line visible so the customer can remove it instead of bricking the cart.
            ProductEntity product = products.get(item.getProductId());
            if (product == null) {
                items.add(new CartItemResponse(item.getId(), item.getProductId(),
                        "Sản phẩm không còn tồn tại", null, null, 0, item.getQuantity(), 0, 0, 0,
                        item.getCustomizationText()));
                continue;
            }
            long gross = Math.multiplyExact(product.getUnitPrice(), item.getQuantity());
            long lineDiscount = Math.multiplyExact(gross, product.getDiscountPercent()) / 100;
            subtotal = Math.addExact(subtotal, gross);
            discount = Math.addExact(discount, lineDiscount);
            items.add(new CartItemResponse(item.getId(), product.getId(), product.getName(),
                    product.getSku(), images.getOrDefault(product.getId(), product.getImageUrl()),
                    product.getUnitPrice(), item.getQuantity(), product.getDiscountPercent(),
                    lineDiscount, gross - lineDiscount, item.getCustomizationText()));
        }
        return new CartResponse(cart.getId(), List.copyOf(items), subtotal, discount, subtotal - discount);
    }
}
