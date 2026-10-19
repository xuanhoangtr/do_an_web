package com.utewedding.app.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Seller-editable product content. Moderation, publication, and stock are never client-editable fields. */
public class VendorProductForm {
    @NotNull(message = "Danh mục là bắt buộc.")
    @Positive(message = "Mã danh mục không hợp lệ.")
    private Long categoryId;
    @NotBlank(message = "Tên sản phẩm không được để trống.")
    @Size(max = 120, message = "Tên sản phẩm tối đa 120 ký tự.")
    private String name;
    @Size(max = 80, message = "SKU tối đa 80 ký tự.")
    private String sku;
    @Size(max = 500, message = "Mô tả ngắn tối đa 500 ký tự.")
    private String shortDescription;
    @Size(max = 4000, message = "Mô tả tối đa 4000 ký tự.")
    private String description;
    @Size(max = 2000, message = "Hướng dẫn tùy chỉnh tối đa 2000 ký tự.")
    private String customizationGuide;
    @Min(value = 0, message = "Giá bán không được âm.")
    private long unitPrice;
    @DecimalMin(value = "0.00", message = "Giá gốc không được âm.")
    @Digits(integer = 17, fraction = 2, message = "Giá gốc tối đa 17 chữ số nguyên và 2 chữ số thập phân.")
    private BigDecimal compareAtPrice;
    @Size(max = 160, message = "Chất liệu tối đa 160 ký tự.")
    private String material;
    @Min(value = 0, message = "Mức giảm giá không được âm.")
    @jakarta.validation.constraints.Max(value = 100, message = "Mức giảm giá tối đa 100%.")
    private int discountPercent;
    @Min(value = 1, message = "Số lượng đặt tối thiểu phải từ 1 trở lên.")
    private int minOrderQuantity = 1;
    @PositiveOrZero(message = "Khối lượng không được âm.")
    private Integer weightGrams;
    @Size(max = 160, message = "Kích thước tối đa 160 ký tự.")
    private String dimensions;
    private boolean customizable;
    private UUID managedMediaId;
    private Long version;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long value) { categoryId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getSku() { return sku; }
    public void setSku(String value) { sku = value; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String value) { shortDescription = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getCustomizationGuide() { return customizationGuide; }
    public void setCustomizationGuide(String value) { customizationGuide = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { unitPrice = value; }
    public BigDecimal getCompareAtPrice() { return compareAtPrice; }
    public void setCompareAtPrice(BigDecimal value) { compareAtPrice = value; }
    public String getMaterial() { return material; }
    public void setMaterial(String value) { material = value; }
    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int value) { discountPercent = value; }
    public int getMinOrderQuantity() { return minOrderQuantity; }
    public void setMinOrderQuantity(int value) { minOrderQuantity = value; }
    public Integer getWeightGrams() { return weightGrams; }
    public void setWeightGrams(Integer value) { weightGrams = value; }
    public String getDimensions() { return dimensions; }
    public void setDimensions(String value) { dimensions = value; }
    public boolean isCustomizable() { return customizable; }
    public void setCustomizable(boolean value) { customizable = value; }
    public UUID getManagedMediaId() { return managedMediaId; }
    public void setManagedMediaId(UUID value) { managedMediaId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
