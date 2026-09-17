package com.utewedding.app.service.web;

import com.utewedding.app.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.utewedding.app.domain.WeddingStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.utewedding.app.service.PasswordPolicy;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Small view models deliberately separate Thymeleaf's naming contract from JPA
 * entities and API response records.
 */
public final class WebViewModels {

    private WebViewModels() {
    }

    public record CategoryView(Long id, String name, String slug, String description) {
    }

    public record TemplateView(UUID id, String key, boolean premium, int displayOrder) {
    }

    public record WeddingSummaryView(UUID id, String slug, String internalTitle, WeddingStatus status,
                                     String previewToken, java.time.LocalDate weddingDate, String coverImageUrl,
                                     int guestCount, int attendingCount, int declinedCount, int pendingCount,
                                     int pendingWishCount) {
        public WeddingSummaryView(UUID id, String slug, String internalTitle, WeddingStatus status) {
            this(id, slug, internalTitle, status, null, null, null, 0, 0, 0, 0, 0);
        }

        public WeddingSummaryView(UUID id, String slug, String internalTitle, WeddingStatus status,
                                  String previewToken) {
            this(id, slug, internalTitle, status, previewToken, null, null, 0, 0, 0, 0, 0);
        }
    }

    public static final class ProductView {
        private final UUID id;
        private final String slug;
        private final String name;
        private final String category;
        private final String imageUrl;
        private final List<String> galleryImages;
        private final BigDecimal unitPrice;
        private final BigDecimal originalPrice;
        private final int discountPercent;
        private final BigDecimal rating;
        private final int reviewCount;
        private final String description;
        private final String material;
        private final int inventory;
        private final boolean active;
        private final String shopName;
        private final int minOrderQuantity;
        private final boolean customizable;
        private final String customizationGuide;
        private final String sku;

        public ProductView(UUID id, String name, String category, String imageUrl,
                           BigDecimal unitPrice, BigDecimal rating, int reviewCount,
                           String description, String material, int inventory,
                           boolean active, String shopName) {
            this(id, null, name, category, imageUrl,
                    imageUrl == null || imageUrl.isBlank() ? List.of() : List.of(imageUrl),
                    unitPrice, null, 0, rating, reviewCount, description, material,
                    inventory, active, shopName);
        }

        public ProductView(UUID id, String name, String category, String imageUrl,
                           List<String> galleryImages, BigDecimal unitPrice,
                           BigDecimal originalPrice, BigDecimal rating, int reviewCount,
                           String description, String material, int inventory,
                           boolean active, String shopName) {
            this(id, null, name, category, imageUrl, galleryImages, unitPrice, originalPrice,
                    0, rating, reviewCount, description, material, inventory, active, shopName);
        }

        public ProductView(UUID id, String name, String category, String imageUrl,
                           List<String> galleryImages, BigDecimal unitPrice,
                           BigDecimal originalPrice, int discountPercent,
                           BigDecimal rating, int reviewCount, String description,
                           String material, int inventory, boolean active, String shopName) {
            this(id, null, name, category, imageUrl, galleryImages, unitPrice, originalPrice,
                    discountPercent, rating, reviewCount, description, material, inventory, active, shopName,
                    1, false, null);
        }

        public ProductView(UUID id, String slug, String name, String category, String imageUrl,
                           List<String> galleryImages, BigDecimal unitPrice,
                           BigDecimal originalPrice, int discountPercent,
                           BigDecimal rating, int reviewCount, String description,
                           String material, int inventory, boolean active, String shopName) {
            this(id, slug, name, category, imageUrl, galleryImages, unitPrice, originalPrice,
                    discountPercent, rating, reviewCount, description, material, inventory, active, shopName,
                    1, false, null);
        }

        public ProductView(UUID id, String slug, String name, String category, String imageUrl,
                           List<String> galleryImages, BigDecimal unitPrice,
                           BigDecimal originalPrice, int discountPercent,
                           BigDecimal rating, int reviewCount, String description,
                           String material, int inventory, boolean active, String shopName,
                           int minOrderQuantity, boolean customizable, String customizationGuide) {
            this(id, slug, name, category, imageUrl, galleryImages, unitPrice, originalPrice,
                    discountPercent, rating, reviewCount, description, material, inventory, active,
                    shopName, minOrderQuantity, customizable, customizationGuide, null);
        }

        public ProductView(UUID id, String slug, String name, String category, String imageUrl,
                           List<String> galleryImages, BigDecimal unitPrice,
                           BigDecimal originalPrice, int discountPercent,
                           BigDecimal rating, int reviewCount, String description,
                           String material, int inventory, boolean active, String shopName,
                           int minOrderQuantity, boolean customizable, String customizationGuide,
                           String sku) {
            this.id = id;
            this.slug = slug != null && !slug.isBlank() ? slug : (id != null ? id.toString() : "");
            this.name = name;
            this.category = category;
            this.imageUrl = imageUrl;
            this.galleryImages = galleryImages == null ? List.of() : List.copyOf(galleryImages);
            this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            this.originalPrice = originalPrice;
            this.discountPercent = discountPercent;
            this.rating = rating == null ? BigDecimal.ZERO : rating;
            this.reviewCount = reviewCount;
            this.description = description;
            this.material = material;
            this.inventory = inventory;
            this.active = active;
            this.shopName = shopName == null || shopName.isBlank() ? "UTEWedding" : shopName;
            this.minOrderQuantity = Math.max(1, minOrderQuantity);
            this.customizable = customizable;
            this.customizationGuide = customizationGuide;
            this.sku = sku;
        }

        public UUID getId() { return id; }
        public String getSlug() { return slug; }
        public String getName() { return name; }
        public String getCategory() { return category; }
        public String getImageUrl() { return imageUrl; }
        public List<String> getGalleryImages() { return galleryImages; }
        public BigDecimal getOriginalPrice() { return originalPrice; }
        public int getDiscountPercent() { return discountPercent; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public BigDecimal getRating() { return rating; }
        public int getReviewCount() { return reviewCount; }
        public String getDescription() { return description; }
        public String getMaterial() { return material; }
        public int getMinOrderQuantity() { return minOrderQuantity; }
        public boolean isCustomizable() { return customizable; }
        public String getCustomizationGuide() { return customizationGuide; }
        public List<String> getPhotoSizeOptions() {
            return com.utewedding.app.util.ProductOptions.photoSizes(customizationGuide);
        }
        public String getSku() { return sku; }
        public int getInventory() { return inventory; }
        public boolean isActive() { return active; }
        public String getShopName() { return shopName; }
    }

    public static final class CartItemView {
        private final UUID id;
        private final UUID productId;
        private final String sku;
        private final String name;
        private final long unitPrice;
        private final int quantity;
        private final int discountPercent;
        private final long discountAmount;
        private final long lineTotal;
        private final String imageUrl;
        private final String shopName;
        private final String customizationText;

        public CartItemView(UUID id, UUID productId, String sku, String name, long unitPrice,
                            int quantity, int discountPercent, long discountAmount,
                            long lineTotal, String imageUrl,
                            String shopName, String customizationText) {
            this.id = id;
            this.productId = productId;
            this.sku = sku;
            this.name = name;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.discountPercent = discountPercent;
            this.discountAmount = discountAmount;
            this.lineTotal = lineTotal;
            this.imageUrl = imageUrl;
            this.shopName = shopName == null ? "UTEWedding" : shopName;
            this.customizationText = customizationText;
        }

        public UUID getId() { return id; }
        public UUID getProductId() { return productId; }
        public String getSku() { return sku; }
        public String getName() { return name; }
        public long getUnitPrice() { return unitPrice; }
        public int getQuantity() { return quantity; }
        public int getDiscountPercent() { return discountPercent; }
        public long getDiscountAmount() { return discountAmount; }
        public long getLineTotal() { return lineTotal; }
        public String getImageUrl() { return imageUrl; }
        public String getShopName() { return shopName; }
        public String getCustomizationText() { return customizationText; }
    }

    public static final class ShopGroupView {
        private final String shopName;
        private final List<CartItemView> items;

        public ShopGroupView(String shopName, List<CartItemView> items) {
            this.shopName = shopName;
            this.items = List.copyOf(items);
        }

        public String getShopName() { return shopName; }
        public List<CartItemView> getItems() { return items; }
    }

    public static final class CartView {
        private final UUID id;
        private final List<CartItemView> items;
        private final List<ShopGroupView> shopGroups;
        private final long subtotal;
        private final long shippingFee;
        private final long productDiscountAmount;
        private final long voucherDiscountAmount;
        private final long grandTotal;

        public CartView(UUID id, List<CartItemView> items, long subtotal,
                        long shippingFee, long productDiscountAmount,
                        long voucherDiscountAmount, long grandTotal) {
            this.id = id;
            this.items = List.copyOf(items);
            this.shopGroups = items.stream().collect(java.util.stream.Collectors.groupingBy(
                    CartItemView::getShopName, java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toList())).entrySet().stream()
                    .map(entry -> new ShopGroupView(entry.getKey(), entry.getValue()))
                    .toList();
            this.subtotal = subtotal;
            this.shippingFee = shippingFee;
            this.productDiscountAmount = productDiscountAmount;
            this.voucherDiscountAmount = voucherDiscountAmount;
            this.grandTotal = grandTotal;
        }

        public CartView(UUID id, List<CartItemView> items, long subtotal,
                        long shippingFee, long discountAmount, long grandTotal) {
            this(id, items, subtotal, shippingFee, discountAmount, 0, grandTotal);
        }

        public UUID getId() { return id; }
        public List<CartItemView> getItems() { return items; }
        public List<ShopGroupView> getShopGroups() { return shopGroups; }
        public long getSubtotal() { return subtotal; }
        public long getShippingFee() { return shippingFee; }
        public long getProductDiscountAmount() { return productDiscountAmount; }
        public long getVoucherDiscountAmount() { return voucherDiscountAmount; }
        public long getDiscountAmount() { return productDiscountAmount + voucherDiscountAmount; }
        public long getGrandTotal() { return grandTotal; }
        public long getTotal() { return grandTotal; }
        public int getItemCount() { return items.stream().mapToInt(CartItemView::getQuantity).sum(); }
        public boolean isEmpty() { return items.isEmpty(); }
    }

        public enum PaymentOption {
            COD("COD", "Thanh toán khi nhận hàng", true),
            VNPAY("VNPAY", "Thanh toán bằng QR hoặc thẻ trên cổng VNPAY", true),
            MOMO("MoMo", "Thanh toán online qua MoMo", false);

        private final String label;
        private final String description;
        private final boolean enabled;

        PaymentOption(String label, String description, boolean enabled) {
            this.label = label;
            this.description = description;
            this.enabled = enabled;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
        public boolean isEnabled() { return enabled; }
        public com.utewedding.app.domain.PaymentMethod toDomain() {
            return com.utewedding.app.domain.PaymentMethod.valueOf(name());
        }
    }

    public static final class CheckoutForm {
        @jakarta.validation.constraints.NotBlank(message = "Họ và tên là bắt buộc.")
        @jakarta.validation.constraints.Size(max = 160)
        private String fullName;
        @jakarta.validation.constraints.NotBlank(message = "Số điện thoại là bắt buộc.")
        @jakarta.validation.constraints.Pattern(regexp = "^[0-9+() .-]{8,40}$",
                message = "Số điện thoại không hợp lệ.")
        private String phone;
        @jakarta.validation.constraints.NotBlank(message = "Địa chỉ giao hàng là bắt buộc.")
        @jakarta.validation.constraints.Size(max = 500)
        private String shippingAddress;
        @jakarta.validation.constraints.NotBlank(message = "Vui lòng chọn tỉnh/thành.")
        @jakarta.validation.constraints.Size(max = 20) private String provinceCode;
        @jakarta.validation.constraints.NotBlank(message = "Vui lòng chọn phường/xã.")
        @jakarta.validation.constraints.Size(max = 20) private String wardCode;
        private UUID shippingAddressId;
        @jakarta.validation.constraints.NotNull(message = "Vui lòng chọn phương thức thanh toán.")
        private PaymentOption paymentMethod = PaymentOption.COD;
        private String discountCode;
        private UUID weddingId;
        @jakarta.validation.constraints.NotBlank(message = "Thiếu mã phiên thanh toán. Vui lòng tải lại trang.")
        @jakarta.validation.constraints.Size(max = 120) private String idempotencyKey;
        private Long expectedTotalAmount;
        private UUID shippingQuoteId;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getShippingAddress() { return shippingAddress; }
        public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }
        public String getWardCode() { return wardCode; }
        public void setWardCode(String wardCode) { this.wardCode = wardCode; }
        public String getProvinceCode() { return provinceCode; }
        public void setProvinceCode(String provinceCode) { this.provinceCode = provinceCode; }
        public UUID getShippingAddressId() { return shippingAddressId; }
        public void setShippingAddressId(UUID shippingAddressId) { this.shippingAddressId = shippingAddressId; }
        public PaymentOption getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(PaymentOption paymentMethod) { this.paymentMethod = paymentMethod; }
        public String getDiscountCode() { return discountCode; }
        public void setDiscountCode(String discountCode) { this.discountCode = discountCode; }
        public UUID getWeddingId() { return weddingId; }
        public void setWeddingId(UUID weddingId) { this.weddingId = weddingId; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public Long getExpectedTotalAmount() { return expectedTotalAmount; }
        public void setExpectedTotalAmount(Long expectedTotalAmount) { this.expectedTotalAmount = expectedTotalAmount; }
        public UUID getShippingQuoteId() { return shippingQuoteId; }
        public void setShippingQuoteId(UUID shippingQuoteId) { this.shippingQuoteId = shippingQuoteId; }
    }

    public static final class WishForm {
        @Size(max = 160, message = "Tên người gửi không được quá 160 ký tự.")
        private String senderName = "";
        @NotBlank(message = "Vui lòng nhập lời chúc.")
        @Size(max = 2000, message = "Lời chúc không được quá 2000 ký tự.")
        private String message;
        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static final class ReviewForm {
        private String authorName;
        private int rating = 5;
        private String comment;
        public String getAuthorName() { return authorName; }
        public void setAuthorName(String authorName) { this.authorName = authorName; }
        public int getRating() { return rating; }
        public void setRating(int rating) { this.rating = rating; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    public static final class RegistrationForm {
        @NotBlank @Size(max = 120)
        private String displayName;
        @NotBlank(message = "Vui lòng nhập tên đăng nhập.") @Size(max = 64)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Tên đăng nhập chỉ gồm chữ cái, số, dấu chấm, gạch dưới hoặc gạch ngang.")
        private String username;
        @NotBlank(message = "Vui lòng nhập email.") @Email(message = "Email không hợp lệ.") @Size(max = 320)
        private String email;
        @NotBlank @Size(min = 6, max = 128, message = "Mật khẩu phải có từ 6 đến 128 ký tự.")
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        private String password;
        @NotBlank
        private String confirmPassword;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username == null ? null : username.trim(); }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email == null ? null : email.trim(); }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getConfirmPassword() { return confirmPassword; }
        public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
    }

    public static final class ForgotPasswordForm {
        @NotBlank(message = "Vui lòng nhập email.")
        @Email(message = "Email không hợp lệ.")
        @Size(max = 320, message = "Email không hợp lệ.")
        private String email;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email == null ? null : email.trim(); }
    }

    public static final class ResetPasswordForm {
        @Size(max = 512, message = "Liên kết khôi phục không hợp lệ.")
        private String token;
        @Email(message = "Email không hợp lệ.")
        @Size(max = 320, message = "Email không hợp lệ.")
        private String email;
        @Pattern(regexp = "^(?:|\\d{6})$", message = "OTP phải gồm 6 chữ số.")
        private String otp;
        @NotBlank(message = PasswordPolicy.MESSAGE)
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.MESSAGE)
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        private String newPassword;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? null : token.trim(); }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email == null || email.isBlank() ? null : email.trim(); }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp == null || otp.isBlank() ? null : otp.trim(); }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    public static final class WeddingForm {
        private UUID id;
        @jakarta.validation.constraints.Size(max = 60)
        @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9-]*$",
                message = "Đường dẫn chỉ gồm chữ, số và dấu gạch ngang.")
        private String slug;
        @NotBlank @Size(max = 120)
        private String brideName;
        @NotBlank @Size(max = 120)
        private String groomName;
        @Size(max = 3000)
        private String story;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate weddingDate;
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime weddingTime;
        @Size(max = 200)
        private String venue;
        @Size(max = 500)
        private String address;
        @Size(max = 2048)
        private String mapUrl;
        private String coverImageUrl;
        private String previewToken;
        private WeddingStatus status;
        private List<GuestView> guests = new ArrayList<>();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getBrideName() { return brideName; }
        public void setBrideName(String brideName) { this.brideName = brideName; }
        public String getGroomName() { return groomName; }
        public void setGroomName(String groomName) { this.groomName = groomName; }
        public String getStory() { return story; }
        public void setStory(String story) { this.story = story; }
        public LocalDate getWeddingDate() { return weddingDate; }
        public void setWeddingDate(LocalDate weddingDate) { this.weddingDate = weddingDate; }
        public LocalTime getWeddingTime() { return weddingTime; }
        public void setWeddingTime(LocalTime weddingTime) { this.weddingTime = weddingTime; }
        public String getVenue() { return venue; }
        public void setVenue(String venue) { this.venue = venue; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getMapUrl() { return mapUrl; }
        public void setMapUrl(String mapUrl) { this.mapUrl = mapUrl; }
        public String getCoverImageUrl() { return coverImageUrl; }
        public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
        public String getPreviewToken() { return previewToken; }
        public void setPreviewToken(String previewToken) { this.previewToken = previewToken; }
        public WeddingStatus getStatus() { return status; }
        public void setStatus(WeddingStatus status) { this.status = status; }
        public List<GuestView> getGuests() { return guests; }
        public void setGuests(List<GuestView> guests) { this.guests = guests == null ? new ArrayList<>() : new ArrayList<>(guests); }
    }

    public static final class GuestView {
        private final UUID id;
        private final String name;
        private final String relationship;
        private final String token;

        public GuestView(UUID id, String name, String relationship, String token) {
            this.id = id;
            this.name = name;
            this.relationship = relationship;
            this.token = token;
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getRelationship() { return relationship; }
        public String getToken() { return token; }
    }

    public static final class PublicWeddingView {
        private boolean available;
        private String slug;
        private String coupleName;
        private String guestName;
        private String brideName;
        private String groomName;
        private LocalDate weddingDate;
        private LocalTime weddingTime;
        private String story;
        private String venue;
        private String address;
        private String mapUrl;
        private String coverImageUrl;
        private List<String> wishes = List.of();
        private boolean invalidGuest;
        private boolean preview;
        private String rsvpStatus;
        private Integer rsvpAttendees;
        private Integer rsvpInviteeCount;
        private String rsvpNote;

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getCoupleName() { return coupleName; }
        public void setCoupleName(String coupleName) { this.coupleName = coupleName; }
        public String getGuestName() { return guestName; }
        public void setGuestName(String guestName) { this.guestName = guestName; }
        public String getBrideName() { return brideName; }
        public void setBrideName(String brideName) { this.brideName = brideName; }
        public String getGroomName() { return groomName; }
        public void setGroomName(String groomName) { this.groomName = groomName; }
        public LocalDate getWeddingDate() { return weddingDate; }
        public void setWeddingDate(LocalDate weddingDate) { this.weddingDate = weddingDate; }
        public LocalTime getWeddingTime() { return weddingTime; }
        public void setWeddingTime(LocalTime weddingTime) { this.weddingTime = weddingTime; }
        public String getStory() { return story; }
        public void setStory(String story) { this.story = story; }
        public String getVenue() { return venue; }
        public void setVenue(String venue) { this.venue = venue; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getMapUrl() { return mapUrl; }
        public void setMapUrl(String mapUrl) { this.mapUrl = mapUrl; }
        public String getCoverImageUrl() { return coverImageUrl; }
        public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
        public List<String> getWishes() { return wishes; }
        public void setWishes(List<String> wishes) { this.wishes = wishes == null ? List.of() : List.copyOf(wishes); }
        public boolean isInvalidGuest() { return invalidGuest; }
        public void setInvalidGuest(boolean invalidGuest) { this.invalidGuest = invalidGuest; }
        public boolean isPreview() { return preview; }
        public void setPreview(boolean preview) { this.preview = preview; }
        public String getRsvpStatus() { return rsvpStatus; }
        public void setRsvpStatus(String rsvpStatus) { this.rsvpStatus = rsvpStatus; }
        public Integer getRsvpAttendees() { return rsvpAttendees; }
        public void setRsvpAttendees(Integer rsvpAttendees) { this.rsvpAttendees = rsvpAttendees; }
        public Integer getRsvpInviteeCount() { return rsvpInviteeCount; }
        public void setRsvpInviteeCount(Integer rsvpInviteeCount) { this.rsvpInviteeCount = rsvpInviteeCount; }
        public String getRsvpNote() { return rsvpNote; }
        public void setRsvpNote(String rsvpNote) { this.rsvpNote = rsvpNote; }
    }

    public static final class RsvpForm {
        private String token;
        private String guestName;
        private String publicResponseId = UUID.randomUUID().toString();
        private String attendanceStatus = "ATTENDING";
        private int attendeeCount = 1;
        private String note;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getGuestName() { return guestName; }
        public void setGuestName(String value) { guestName = value; }
        public String getPublicResponseId() { return publicResponseId; }
        public void setPublicResponseId(String value) { publicResponseId = value; }
        public String getAttendanceStatus() { return attendanceStatus; }
        public void setAttendanceStatus(String attendanceStatus) { this.attendanceStatus = attendanceStatus; }
        public int getAttendeeCount() { return attendeeCount; }
        public void setAttendeeCount(int attendeeCount) { this.attendeeCount = attendeeCount; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public static final class AdminUserView {
        private final UUID id;
        private final String username;
        private final String displayName;
        private final String role;
        private final String status;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final Instant deletedAt;

        public AdminUserView(UUID id, String username, String displayName, String role,
                             Instant createdAt, boolean active) {
            this(id, username, displayName, role, active ? "ACTIVE" : "SUSPENDED",
                    createdAt, createdAt, null);
        }

        public AdminUserView(UUID id, String username, String displayName, String role, String status,
                             Instant createdAt, Instant updatedAt, Instant deletedAt) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.role = role;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.deletedAt = deletedAt;
        }

        public UUID getId() { return id; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public Instant getDeletedAt() { return deletedAt; }
        public boolean isActive() { return "ACTIVE".equals(status); }
        public boolean isDeleted() { return deletedAt != null || "DELETED".equals(status); }
    }

    public enum AdminOrderStatus {
        PENDING("Chờ thanh toán"), PAYMENT_REVIEW("Đang xác minh thanh toán"), PAID("Đã thanh toán"), CANCELLED("Đã hủy"),
        EXPIRED("Hết hạn"), REFUNDED("Đã hoàn tiền");

        private final String description;
        AdminOrderStatus(String description) { this.description = description; }
        public String getDescription() { return description; }
        public static AdminOrderStatus from(OrderStatus status) {
            return valueOf(status.name());
        }
    }

    public static final class AdminOrderView {
        private final UUID id;
        private final String orderNumber;
        private final String buyerName;
        private final String phone;
        private final String productSummary;
        private final long total;
        private final AdminOrderStatus status;
        private String paymentMethod;
        private final Instant createdAt;
        private final String shippingAddress;
        private final String trackingNumber;
        private final UUID shipperId;

        public AdminOrderView(UUID id, String orderNumber, String buyerName, String phone,
                              String productSummary, long total,
                              AdminOrderStatus status, Instant createdAt, String shippingAddress,
                              String trackingNumber, UUID shipperId) {
            this.id = id; this.orderNumber = orderNumber; this.buyerName = buyerName;
            this.phone = phone; this.productSummary = productSummary;
            this.total = total; this.status = status; this.createdAt = createdAt;
            this.shippingAddress = shippingAddress;
            this.trackingNumber = trackingNumber;
            this.shipperId = shipperId;
        }

        public UUID getId() { return id; }
        public String getOrderNumber() { return orderNumber; }
        public String getBuyerName() { return buyerName; }
        public String getPhone() { return phone; }
        public String getProductSummary() { return productSummary; }
        public long getTotal() { return total; }
        public AdminOrderStatus getStatus() { return status; }
        public String getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(String value) { paymentMethod = value; }
        public String getStatusDescription() {
            return status == AdminOrderStatus.PENDING && "COD".equalsIgnoreCase(paymentMethod)
                    ? "COD · Chờ giao và thu tiền" : status.getDescription();
        }
        public String getStatusBadgeClass() {
            return switch (status) {
                case PENDING, PAYMENT_REVIEW -> "text-bg-warning";
                case PAID -> "text-bg-info";
                case CANCELLED, EXPIRED, REFUNDED -> "text-bg-secondary";
            };
        }
        public Instant getCreatedAt() { return createdAt; }
        public String getShippingAddress() { return shippingAddress; }
        public String getTrackingNumber() { return trackingNumber; }
        public String getTrackingCode() { return trackingNumber; }
        public UUID getShipperId() { return shipperId; }
    }
}
