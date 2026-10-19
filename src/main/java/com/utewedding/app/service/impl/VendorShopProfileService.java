package com.utewedding.app.service.impl;

import com.utewedding.app.config.CloudinaryConfig;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.ShopApplicationStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.domain.entity.ShopApplicationEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.dto.request.VendorShopProfileForm;
import com.utewedding.app.dto.response.VendorShopProfileView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.ShopApplicationRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.TextSanitizer;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Owner-only profile and shop media operations. The shop identity always comes from the checked workspace. */
@Service
public class VendorShopProfileService {
    private static final String LOGO = "SHOP_LOGO";
    private static final String BANNER = "SHOP_BANNER";
    private static final String EVIDENCE = "SHOP_APPLICATION_EVIDENCE";

    private final VendorWorkspaceContext workspaceContext;
    private final ShopAccessPolicy shopAccessPolicy;
    private final ShopRepository shops;
    private final ShopApplicationRepository applications;
    private final ManagedMediaObjectRepository mediaRepository;
    private final ManagedMediaService mediaService;
    private final CloudinaryPrivateMediaProxy privateMediaProxy;
    private final CloudinaryConfig cloudinaryConfig;
    private final TextSanitizer sanitizer;

    public VendorShopProfileService(VendorWorkspaceContext workspaceContext, ShopAccessPolicy shopAccessPolicy,
                                    ShopRepository shops, ShopApplicationRepository applications,
                                    ManagedMediaObjectRepository mediaRepository, ManagedMediaService mediaService,
                                    CloudinaryPrivateMediaProxy privateMediaProxy, CloudinaryConfig cloudinaryConfig,
                                    TextSanitizer sanitizer) {
        this.workspaceContext = workspaceContext;
        this.shopAccessPolicy = shopAccessPolicy;
        this.shops = shops;
        this.applications = applications;
        this.mediaRepository = mediaRepository;
        this.mediaService = mediaService;
        this.privateMediaProxy = privateMediaProxy;
        this.cloudinaryConfig = cloudinaryConfig;
        this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public VendorShopProfileView profile(UserPrincipal actor) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        ShopEntity shop = shops.findById(workspace.shopId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        ShopApplicationEntity latest = applications.findTopByShopIdOrderByAttemptNumberDesc(shop.getId()).orElse(null);
        List<VendorShopProfileView.EvidenceFile> evidence = mediaRepository
                .findByShopIdAndPurposeAndStatusAndDeliveryTypeOrderByCreatedAtDesc(
                        shop.getId(), EVIDENCE, ManagedMediaStatus.READY, "authenticated")
                .stream()
                .filter(media -> media.getApplicationId() != null)
                .map(media -> new VendorShopProfileView.EvidenceFile(media.getId(),
                        media.getByteSize() == null ? 0 : media.getByteSize(),
                        media.getDetectedMime(), media.getCreatedAt()))
                .toList();
        return new VendorShopProfileView(shop.getCode(), shop.getDisplayName(), shop.getDescription(),
                shop.getContactEmail(), shop.getContactPhone(), publicImageUrl(shop.getLogoMediaId(), shop.getId(), LOGO),
                publicImageUrl(shop.getBannerMediaId(), shop.getId(), BANNER), shop.getVersion(),
                latest == null ? null : latest.getStatus().name(), evidence);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(UserPrincipal actor, VendorShopProfileForm form) {
        if (form == null) throw new BusinessConflictException("Thông tin hồ sơ không hợp lệ.");
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        ShopEntity shop = shopAccessPolicy.requireMutation(actor, workspace.shopId()).shop();
        if (form.getVersion() != shop.getVersion()) {
            throw new BusinessConflictException("Hồ sơ vừa được cập nhật ở nơi khác. Vui lòng tải lại trang.");
        }
        String name = sanitizer.trimToNull(form.getShopName(), 160);
        if (name == null) throw new BusinessConflictException("Tên cửa hàng là bắt buộc.");
        String email = sanitizer.trimToNull(form.getContactEmail(), 320);
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessConflictException("Email liên hệ không hợp lệ.");
        }
        String phone = sanitizer.trimToNull(form.getContactPhone(), 30);
        if (phone != null && !phone.matches("^[+0-9() .-]{7,30}$")) {
            throw new BusinessConflictException("Số điện thoại liên hệ không hợp lệ.");
        }
        shop.setDisplayName(name);
        shop.setDescription(sanitizer.trimToNull(form.getDescription(), 2000));
        shop.setContactEmail(email);
        shop.setContactPhone(phone);
        shops.save(shop);
    }

    public void uploadLogo(UserPrincipal actor, MultipartFile file) {
        uploadPublicImage(actor, file, LOGO);
    }

    public void uploadBanner(UserPrincipal actor, MultipartFile file) {
        uploadPublicImage(actor, file, BANNER);
    }

    private void uploadPublicImage(UserPrincipal actor, MultipartFile file, String purpose) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        mediaService.uploadForShop(file, actor.getId(), workspace.shopId(), null, purpose, uploaded -> {
            ShopEntity shop = shopAccessPolicy.requireMutation(actor, workspace.shopId()).shop();
            UUID previousId = LOGO.equals(purpose) ? shop.getLogoMediaId() : shop.getBannerMediaId();
            if (LOGO.equals(purpose)) shop.setLogoMediaId(uploaded.getId());
            else shop.setBannerMediaId(uploaded.getId());
            shops.save(shop);
            queueOldPublicImage(previousId, workspace.shopId(), purpose);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeLogo(UserPrincipal actor) {
        removePublicImage(actor, LOGO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeBanner(UserPrincipal actor) {
        removePublicImage(actor, BANNER);
    }

    private void removePublicImage(UserPrincipal actor, String purpose) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        ShopEntity shop = shopAccessPolicy.requireMutation(actor, workspace.shopId()).shop();
        UUID oldId = LOGO.equals(purpose) ? shop.getLogoMediaId() : shop.getBannerMediaId();
        if (LOGO.equals(purpose)) shop.setLogoMediaId(null);
        else shop.setBannerMediaId(null);
        shops.save(shop);
        queueOldPublicImage(oldId, workspace.shopId(), purpose);
    }

    private void queueOldPublicImage(UUID mediaId, UUID shopId, String purpose) {
        if (mediaId == null) return;
        mediaRepository.findByIdAndShopIdAndPurposeAndStatusAndDeliveryType(
                        mediaId, shopId, purpose, ManagedMediaStatus.READY, "upload")
                .ifPresent(media -> mediaService.requestDelete(media.getId()));
    }

    public void uploadEvidence(UserPrincipal actor, MultipartFile file) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        ShopApplicationEntity application = applications.findTopByShopIdOrderByAttemptNumberDesc(workspace.shopId())
                .filter(VendorShopProfileService::applicationAllowsEvidence)
                .orElseThrow(() -> new BusinessConflictException("Cửa hàng chưa có hồ sơ có thể bổ sung tài liệu."));
        UUID applicationId = application.getId();
        mediaService.uploadForShop(file, actor.getId(), workspace.shopId(), applicationId, EVIDENCE, uploaded -> {
            shopAccessPolicy.requireMutation(actor, workspace.shopId());
            ShopApplicationEntity current = applications.findTopByShopIdOrderByAttemptNumberDesc(workspace.shopId())
                    .orElseThrow(() -> new BusinessConflictException("Hồ sơ cửa hàng đã thay đổi."));
            if (!applicationId.equals(current.getId()) || !applicationAllowsEvidence(current)) {
                throw new BusinessConflictException("Hồ sơ cửa hàng đã thay đổi. Vui lòng tải lại trang.");
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeEvidence(UserPrincipal actor, UUID mediaId) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        shopAccessPolicy.requireMutation(actor, workspace.shopId());
        ManagedMediaObjectEntity media = requireEvidence(workspace.shopId(), mediaId);
        mediaService.requestDelete(media.getId());
    }

    @Transactional(readOnly = true)
    public EvidenceDownload downloadEvidence(UserPrincipal actor, UUID mediaId) {
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        ManagedMediaObjectEntity media = requireEvidence(workspace.shopId(), mediaId);
        CloudinaryPrivateMediaProxy.OpenedDownload stream = privateMediaProxy.open(media);
        String extension = "image/png".equals(media.getDetectedMime()) ? "png" : "jpg";
        return new EvidenceDownload(stream, "shop-evidence-" + media.getId() + "." + extension);
    }

    private ManagedMediaObjectEntity requireEvidence(UUID shopId, UUID mediaId) {
        if (mediaId == null) throw new ResourceNotFoundException("Không tìm thấy tài liệu.");
        ManagedMediaObjectEntity media = mediaRepository.findByIdAndShopIdAndPurposeAndStatusAndDeliveryType(
                        mediaId, shopId, EVIDENCE, ManagedMediaStatus.READY, "authenticated")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu."));
        if (media.getApplicationId() == null || !shopId.equals(media.getShopId())) {
            throw new ResourceNotFoundException("Không tìm thấy tài liệu.");
        }
        return media;
    }

    private String publicImageUrl(UUID mediaId, UUID shopId, String purpose) {
        if (mediaId == null) return null;
        return mediaRepository.findByIdAndShopIdAndPurposeAndStatusAndDeliveryType(
                        mediaId, shopId, purpose, ManagedMediaStatus.READY, "upload")
                .map(media -> safePublicUrl(media.getSecureUrl()))
                .orElse(null);
    }

    private String safePublicUrl(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw);
            String cloudName = cloudinaryConfig.getCloudName();
            String path = uri.getRawPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && cloudName != null
                    && path != null && path.startsWith("/" + cloudName + "/image/upload/")) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid stored URLs are omitted from the editable page.
        }
        return null;
    }

    private static boolean applicationAllowsEvidence(ShopApplicationEntity application) {
        return application.getStatus() == ShopApplicationStatus.SUBMITTED
                || application.getStatus() == ShopApplicationStatus.APPROVED;
    }

    public record EvidenceDownload(CloudinaryPrivateMediaProxy.OpenedDownload stream, String fileName) { }
}
