package com.utewedding.app.service.impl;

import com.utewedding.app.config.GhnConfig;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.port.ShippingParcel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Builds a stable GHN parcel snapshot from the active cart catalog. */
@Component
public class ShippingParcelFactory {
    private static final Pattern DIMENSION = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private final ProductRepository products;
    private final GhnConfig config;

    public ShippingParcelFactory(ProductRepository products, GhnConfig config) {
        this.products = products;
        this.config = config;
    }

    public ShippingParcel fromCartRows(List<CartItemEntity> rows) {
        List<ShippingParcel.Item> items = new ArrayList<>();
        long totalWeight = 0;
        long totalVolume = 0;
        int maxLength = 1;
        int maxWidth = 1;
        int maxHeight = 1;
        StringBuilder content = new StringBuilder();

        for (CartItemEntity row : rows) {
            ProductEntity product = products.findByIdAndActiveTrue(row.getProductId())
                    .orElseThrow(() -> new BusinessConflictException(
                            "Sản phẩm trong giỏ không còn kinh doanh."));
            int weight = product.getWeightGrams() == null || product.getWeightGrams() <= 0
                    ? config.defaultWeightGrams() : product.getWeightGrams();
            int[] dimensions = dimensions(product.getDimensions());
            int quantity = Math.max(1, row.getQuantity());
            items.add(new ShippingParcel.Item(product.getName(), product.getSku(), quantity,
                    product.getUnitPrice(), weight, dimensions[0], dimensions[1], dimensions[2]));
            totalWeight = Math.addExact(totalWeight, Math.multiplyExact((long) weight, quantity));
            totalVolume = Math.addExact(totalVolume, Math.multiplyExact(
                    Math.multiplyExact(Math.multiplyExact((long) dimensions[0], dimensions[1]), dimensions[2]),
                    quantity));
            maxLength = Math.max(maxLength, dimensions[0]);
            maxWidth = Math.max(maxWidth, dimensions[1]);
            maxHeight = Math.max(maxHeight, dimensions[2]);
            if (content.length() > 0) content.append(", ");
            content.append(product.getName()).append(" x ").append(quantity);
        }

        if (items.isEmpty()) throw new BusinessConflictException("Giỏ hàng đang trống.");
        if (totalWeight > 50_000) {
            throw new BusinessConflictException("GHN chỉ nhận kiện có tổng khối lượng tối đa 50 kg.");
        }
        int length = Math.max(maxLength, ceilCubeRoot(totalVolume));
        int width = Math.max(maxWidth, ceilSqrt(ceilDiv(totalVolume, length)));
        int height = Math.max(maxHeight, Math.toIntExact(ceilDiv(totalVolume, (long) length * width)));
        if (length > 200 || width > 200 || height > 200) {
            throw new BusinessConflictException("Kích thước kiện vượt giới hạn GHN; cần tách kiện.");
        }
        String description = content.length() > 2000 ? content.substring(0, 2000) : content.toString();
        return new ShippingParcel(Math.toIntExact(Math.max(1, totalWeight)), length, width, height,
                description, items);
    }

    public ShippingParcel fromOrderItems(List<com.utewedding.app.domain.entity.OrderItemEntity> items) {
        return fromCartRows(items.stream().map(item -> {
            CartItemEntity row = new CartItemEntity();
            row.setProductId(item.getProductId());
            row.setQuantity(item.getQuantity());
            return row;
        }).toList());
    }

    private int[] dimensions(String source) {
        if (source == null || source.isBlank()) return defaults();
        Matcher matcher = DIMENSION.matcher(source);
        List<Integer> values = new ArrayList<>(3);
        while (matcher.find() && values.size() < 3) {
            try {
                int value = new BigDecimal(matcher.group().replace(',', '.'))
                        .setScale(0, RoundingMode.CEILING).intValueExact();
                if (value <= 0 || value > 200) return defaults();
                values.add(value);
            } catch (ArithmeticException | NumberFormatException ignored) {
                return defaults();
            }
        }
        if (values.size() != 3) return defaults();
        return new int[]{values.get(0), values.get(1), values.get(2)};
    }

    private int[] defaults() {
        return new int[]{config.defaultLengthCm(), config.defaultWidthCm(), config.defaultHeightCm()};
    }

    private int ceilCubeRoot(long value) {
        int root = (int) Math.ceil(Math.cbrt(Math.max(1, value)));
        return Math.max(1, root);
    }

    private int ceilSqrt(long value) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, value))));
    }

    private long ceilDiv(long numerator, long denominator) {
        return Math.max(1, (numerator + denominator - 1) / denominator);
    }
}
