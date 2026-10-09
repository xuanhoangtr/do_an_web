package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ProductReviewEntity;
import com.utewedding.app.domain.entity.ProductReviewMediaEntity;
import com.utewedding.app.domain.entity.ProductReviewReportEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.CreateProductReviewRequest;
import com.utewedding.app.dto.request.ModerateProductReviewRequest;
import com.utewedding.app.dto.request.ReportProductReviewRequest;
import com.utewedding.app.dto.request.UpdateProductReviewRequest;
import com.utewedding.app.dto.response.MessageResponse;
import com.utewedding.app.dto.response.ProductReviewMediaResponse;
import com.utewedding.app.dto.response.ProductReviewResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ProductReviewReportRepository;
import com.utewedding.app.repository.ProductReviewMediaRepository;
import com.utewedding.app.repository.ProductReviewRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ReviewService;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.integration.CloudinaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReviewServiceImpl implements ReviewService {
    private final ProductReviewRepository reviewRepository;
    private final ProductReviewReportRepository reportRepository;
    private final ProductReviewMediaRepository mediaRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AuditLogPort auditLog;
    private final CloudinaryService cloudinaryService;
    private final TextSanitizer textSanitizer;
    private ManagedMediaService managedMediaService;
    private TransactionTemplate mediaTransactions;
    private org.springframework.context.ApplicationEventPublisher events;

    /** Compatibility constructor for focused unit tests. */
    public ReviewServiceImpl(ProductReviewRepository reviewRepository,
                             ProductReviewReportRepository reportRepository,
                             ProductReviewMediaRepository mediaRepository,
                             ProductRepository productRepository,
                             UserRepository userRepository,
                             AuditLogPort auditLog,
                             CloudinaryService cloudinaryService) {
        this(reviewRepository, reportRepository, mediaRepository, productRepository, userRepository,
                auditLog, cloudinaryService, new TextSanitizer());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewServiceImpl(ProductReviewRepository reviewRepository,
                             ProductReviewReportRepository reportRepository,
                             ProductReviewMediaRepository mediaRepository,
                             ProductRepository productRepository,
                             UserRepository userRepository,
                             AuditLogPort auditLog,
                             CloudinaryService cloudinaryService,
                             TextSanitizer textSanitizer) {
        this.reviewRepository = reviewRepository;
        this.reportRepository = reportRepository;
        this.mediaRepository = mediaRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.cloudinaryService = cloudinaryService;
        this.textSanitizer = textSanitizer;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachMediaInfrastructure(ManagedMediaService managedMediaService,
                                   PlatformTransactionManager transactionManager) {
        this.managedMediaService = managedMediaService;
        if (transactionManager != null) this.mediaTransactions = new TransactionTemplate(transactionManager);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAttentionEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> list(UUID productId, int page, int size) {
        if (!productRepository.existsById(productId)) throw new ResourceNotFoundException("Không tìm thấy sản phẩm.");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return toResponsePage(reviewRepository.findByProductIdAndStatusOrderByCreatedAtDesc(
                productId, ProductReviewStatus.APPROVED, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> moderationQueue(int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return toResponsePage(reviewRepository.findAllByOrderByCreatedAtAsc(pageable));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductReviewResponse create(UUID userId, UUID productId, CreateProductReviewRequest request,
                                        RequestContext context) {
        ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        if (!product.isActive()) throw new BusinessConflictException("Sản phẩm không còn hiển thị.");
        if (reviewRepository.existsByProductIdAndUserId(productId, userId))
            throw new BusinessConflictException("Bạn đã đánh giá sản phẩm này.");
        UUID orderId = reviewRepository.findPaidOrderIds(productId, userId).stream().findFirst()
                .orElseThrow(() -> new BusinessConflictException("Chỉ khách đã thanh toán sản phẩm mới được đánh giá."));
        String content = textSanitizer.plainText(request.content());
        if (content.length() < 50) throw new BusinessConflictException("Nội dung đánh giá phải có ít nhất 50 ký tự.");
        ProductReviewEntity review = new ProductReviewEntity();
        review.setProductId(productId);
        review.setUserId(userId);
        review.setOrderId(orderId);
        review.setRating(request.rating());
        review.setContent(content);
        review.setStatus(ProductReviewStatus.PENDING);
        try {
            review = reviewRepository.save(review);
            recalculate(product);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessConflictException("Bạn đã đánh giá sản phẩm này.");
        }
        auditLog.record("REVIEW_CREATED", userId, "ProductReview", review.getId(), java.util.Map.of(), context);
        attentionChanged("ProductReview", review.getId());
        return toResponse(review);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductReviewResponse update(UUID userId, UUID reviewId, UpdateProductReviewRequest request,
                                        RequestContext context) {
        ProductReviewEntity review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        if (!userId.equals(review.getUserId()))
            throw new org.springframework.security.access.AccessDeniedException("Bạn không sở hữu đánh giá này.");
        ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(review.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        if (request.rating() == null && request.content() == null)
            throw new BusinessConflictException("Không có nội dung cập nhật.");
        if (request.rating() != null) review.setRating(request.rating());
        if (request.content() != null) {
            String content = textSanitizer.plainText(request.content());
            if (content.length() < 50 || content.length() > 2000) {
                throw new BusinessConflictException("Nội dung đánh giá phải có từ 50 đến 2.000 ký tự.");
            }
            review.setContent(content);
        }
        returnToModeration(review);
        review = reviewRepository.saveAndFlush(review);
        recalculate(product);
        auditLog.record("REVIEW_UPDATED", userId, "ProductReview", reviewId, java.util.Map.of(), context);
        attentionChanged("ProductReview", reviewId);
        return toResponse(review);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse delete(UUID userId, UUID reviewId, RequestContext context) {
        ProductReviewEntity review = owned(reviewId, userId);
        ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(review.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        reviewRepository.delete(review);
        recalculate(product);
        auditLog.record("REVIEW_DELETED", userId, "ProductReview", reviewId, java.util.Map.of(), context);
        attentionChanged("ProductReview", reviewId);
        return new MessageResponse("Đã xóa đánh giá.");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse report(UUID userId, UUID reviewId, ReportProductReviewRequest request,
                                  RequestContext context) {
        ProductReviewEntity review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        if (reportRepository.findByReviewIdAndReporterId(reviewId, userId).isPresent())
            throw new BusinessConflictException("Bạn đã báo cáo đánh giá này.");
        ProductReviewReportEntity report = new ProductReviewReportEntity();
        report.setReviewId(review.getId());
        report.setReporterId(userId);
        report.setReason(textSanitizer.plainText(request.reason()));
        reportRepository.save(report);
        auditLog.record("REVIEW_REPORTED", userId, "ProductReview", review.getId(), java.util.Map.of(), context);
        attentionChanged("ProductReviewReport", report.getId());
        return new MessageResponse("Đã ghi nhận báo cáo.");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductReviewResponse moderate(UUID adminId, UUID reviewId,
                                          ModerateProductReviewRequest request, RequestContext context) {
        UserRole role = userRepository.findById(adminId).map(user -> user.getRole())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        if (role != UserRole.ADMIN && role != UserRole.MANAGER && role != UserRole.SUPER_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("Bạn không có quyền duyệt đánh giá.");
        ProductReviewEntity review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(review.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
        if (request.status() == null || request.status() == ProductReviewStatus.PENDING) {
            throw new BusinessConflictException("Trạng thái kiểm duyệt đánh giá không hợp lệ.");
        }
        ProductReviewStatus previousStatus = review.getStatus();
        if (request.status() == ProductReviewStatus.HIDDEN && previousStatus != ProductReviewStatus.APPROVED) {
            throw new BusinessConflictException("Chỉ có thể ẩn đánh giá đã được duyệt.");
        }
        if (request.status() == ProductReviewStatus.REJECTED && previousStatus != ProductReviewStatus.PENDING) {
            throw new BusinessConflictException("Chỉ có thể từ chối đánh giá đang chờ duyệt.");
        }
        review.setStatus(request.status());
        review.setModeratedById(adminId);
        review.setModeratedAt(Instant.now());
        review = reviewRepository.save(review);
        recalculate(product);
        RequestContext auditContext = context == null
                ? new RequestContext(null, null, null, role.name())
                : new RequestContext(context.ipAddress(), context.userAgent(), context.requestId(), role.name());
        auditLog.record(request.status() == ProductReviewStatus.REJECTED
                        || request.status() == ProductReviewStatus.HIDDEN ? "REVIEW_HIDDEN" : "REVIEW_RESTORED",
                adminId, "ProductReview", reviewId,
                java.util.Map.of("status", String.valueOf(previousStatus) + " -> " + review.getStatus().name()),
                auditContext);
        attentionChanged("ProductReview", reviewId);
        return toResponse(review);
    }

    @Override
    public List<ProductReviewMediaResponse> uploadMedia(UUID userId, UUID reviewId,
                                                        MultipartFile[] files) {
        ProductReviewEntity review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        if (!userId.equals(review.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Bạn không sở hữu đánh giá này.");
        }
        if (files == null || files.length == 0) {
            throw new BusinessConflictException("Vui lòng chọn ít nhất một ảnh.");
        }
        long existing = mediaRepository.countByReviewId(reviewId);
        if (existing + files.length > 5) {
            throw new BusinessConflictException("Mỗi đánh giá được đính kèm tối đa 5 ảnh.");
        }

        List<StagedReviewMedia> staged = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new BusinessConflictException("Ảnh đánh giá không được để trống.");
            }
        }
        try {
            for (MultipartFile file : files) {
                // Cloudinary runs before any database row lock or transaction is opened.
                var result = cloudinaryService.uploadImage(file, reviewId.toString(), "REVIEW");
                var managed = managedMediaService == null ? null
                        : managedMediaService.trackPublicReviewUpload(file, userId, result);
                ProductReviewMediaEntity media = new ProductReviewMediaEntity();
                media.setReviewId(reviewId);
                media.setManagedMediaId(managed == null ? null : managed.getId());
                media.setPublicId(result.publicId());
                media.setSecureUrl(result.secureUrl());
                media.setMimeType(file.getContentType() == null ? "application/octet-stream"
                        : file.getContentType());
                media.setByteSize(result.bytes() == null ? file.getSize() : result.bytes());
                staged.add(new StagedReviewMedia(file, result.publicId(), media, managed == null ? null : managed.getId()));
            }
        } catch (RuntimeException uploadFailure) {
            cleanupStagedMedia(staged);
            throw uploadFailure;
        }
        try {
            java.util.function.Supplier<List<ProductReviewMediaResponse>> persist = () -> {
                ProductReviewEntity locked = reviewRepository.findByIdForUpdate(reviewId)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
                if (!userId.equals(locked.getUserId())) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Bạn không sở hữu đánh giá này.");
                }
                if (mediaRepository.countByReviewId(reviewId) + staged.size() > 5) {
                    throw new BusinessConflictException("Mỗi đánh giá được đính kèm tối đa 5 ảnh.");
                }
                List<ProductReviewMediaResponse> responses = new ArrayList<>();
                for (StagedReviewMedia item : staged) {
                    responses.add(toMediaResponse(mediaRepository.save(item.media())));
                }
                ProductReviewStatus previousStatus = locked.getStatus();
                returnToModeration(locked);
                reviewRepository.saveAndFlush(locked);
                if (previousStatus == ProductReviewStatus.APPROVED) {
                    ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(locked.getProductId())
                            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
                    recalculate(product);
                }
                return responses;
            };
            return mediaTransactions == null ? persist.get() : mediaTransactions.execute(status -> persist.get());
        } catch (RuntimeException persistenceFailure) {
            cleanupStagedMedia(staged);
            throw persistenceFailure;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageResponse deleteMedia(UUID userId, UUID reviewId, UUID mediaId, RequestContext context) {
        ProductReviewEntity review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        if (!userId.equals(review.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Bạn không sở hữu đánh giá này.");
        }
        ProductReviewMediaEntity media = mediaRepository.findByIdAndReviewId(mediaId, reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ảnh đánh giá."));
        ProductReviewStatus previousStatus = review.getStatus();
        mediaRepository.delete(media);
        returnToModeration(review);
        reviewRepository.saveAndFlush(review);
        if (previousStatus == ProductReviewStatus.APPROVED) {
            ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(review.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
            recalculate(product);
        }
        if (media.getManagedMediaId() != null && managedMediaService != null) {
            managedMediaService.requestDelete(media.getManagedMediaId());
        } else {
            queueLegacyReviewMediaDeletion(media);
        }
        auditLog.record("REVIEW_MEDIA_DELETED", userId, "ProductReview", reviewId,
                java.util.Map.of("mediaId", mediaId.toString(), "status", String.valueOf(previousStatus)
                        + " -> " + review.getStatus().name()), context);
        return new MessageResponse("Đã xóa ảnh; đánh giá đang chờ kiểm duyệt lại.");
    }

    private void queueLegacyReviewMediaDeletion(ProductReviewMediaEntity media) {
        Runnable cleanup = () -> {
            CloudinaryService.DeleteResult result = cloudinaryService.deleteImage(
                    media.getPublicId(), "image", "upload");
            if (result == CloudinaryService.DeleteResult.ERROR) {
                org.slf4j.LoggerFactory.getLogger(ReviewServiceImpl.class).error(
                        "Could not delete legacy review media {} after removal", media.getId());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { cleanup.run(); }
                    });
        } else {
            cleanup.run();
        }
    }

    private void cleanupStagedMedia(List<StagedReviewMedia> staged) {
        for (StagedReviewMedia item : staged) {
            try {
                if (managedMediaService != null && item.managedMediaId() != null) {
                    managedMediaService.abortPublicReviewUpload(item.managedMediaId());
                } else {
                    cloudinaryService.deleteImage(item.publicId(), "image", "upload");
                }
            } catch (RuntimeException ignored) {
                cloudinaryService.deleteImage(item.publicId(), "image", "upload");
            }
        }
    }

    private record StagedReviewMedia(MultipartFile file, String publicId,
                                     ProductReviewMediaEntity media, UUID managedMediaId) { }

    private void returnToModeration(ProductReviewEntity review) {
        if (review.getStatus() == null || review.getStatus() == ProductReviewStatus.PENDING) return;
        review.setStatus(ProductReviewStatus.PENDING);
        review.setModeratedById(null);
        review.setModeratedAt(null);
    }

    @Override
    @Transactional(readOnly = true)
    public com.utewedding.app.dto.response.ReviewEligibility eligibility(UUID userId, UUID productId) {
        if (userId == null) return com.utewedding.app.dto.response.ReviewEligibility.denied("Đăng nhập bằng tài khoản khách hàng để đánh giá.");
        if (reviewRepository.existsByProductIdAndUserId(productId, userId)) {
            return com.utewedding.app.dto.response.ReviewEligibility.denied("Bạn đã gửi đánh giá cho sản phẩm này.");
        }
        if (reviewRepository.findPaidOrderIds(productId, userId).isEmpty()) {
            return com.utewedding.app.dto.response.ReviewEligibility.denied("Chỉ khách đã thanh toán sản phẩm mới được đánh giá.");
        }
        return com.utewedding.app.dto.response.ReviewEligibility.allowed();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> mine(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 20),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProductReviewResponse> basePage = toResponsePage(
                reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable));
        if (basePage.isEmpty()) {
            return basePage;
        }
        Map<UUID, ProductEntity> productsById = productRepository.findAllById(
                        basePage.getContent().stream().map(ProductReviewResponse::productId).distinct().toList())
                .stream().collect(Collectors.toMap(ProductEntity::getId, row -> row));
        return basePage.map(review -> {
            ProductEntity product = productsById.get(review.productId());
            String productName = product == null ? "Sản phẩm không còn tồn tại" : product.getName();
            String productSlug = product != null && product.getSlug() != null && !product.getSlug().isBlank()
                    ? product.getSlug()
                    : (review.productId() == null ? "" : review.productId().toString());
            return new ProductReviewResponse(review.id(), review.productId(), review.userId(), review.orderId(),
                    review.reviewerName(), review.rating(), review.content(), review.status(),
                    review.createdAt(), review.updatedAt(), review.media(), productName, productSlug);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> searchModeration(String query, ProductReviewStatus status, Integer rating,
                                                         UUID productId, UUID userId, java.time.Instant from,
                                                         java.time.Instant to, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String normalized = query == null || query.isBlank() ? null : query.trim();
        return toResponsePage(reviewRepository.searchForModeration(normalized, status, rating, productId, userId,
                from, to, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<com.utewedding.app.service.AdminReviewRow> searchModerationRows(
            String query, ProductReviewStatus status, Integer rating, UUID productId, UUID userId,
            java.time.Instant from, java.time.Instant to, int page, int size) {
        Page<ProductReviewResponse> reviews = searchModeration(query, status, rating, productId, userId,
                from, to, page, size);
        Map<UUID, String> names = new HashMap<>();
        productRepository.findAllById(reviews.getContent().stream().map(ProductReviewResponse::productId).distinct().toList())
                .forEach(product -> names.put(product.getId(), product.getName()));
        return reviews.map(review -> new com.utewedding.app.service.AdminReviewRow(review.id(), review.productId(),
                names.getOrDefault(review.productId(), "Sản phẩm không còn tồn tại"), review.userId(),
                review.reviewerName(), review.rating(), review.content(), review.status(), review.createdAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<com.utewedding.app.domain.entity.ProductReviewReportEntity> reports(
            com.utewedding.app.domain.ProductReviewReportStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        return status == null
                ? reportRepository.findAllByOrderByCreatedAtDesc(pageable)
                : reportRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<com.utewedding.app.service.ReviewReportView> reportViews(
            com.utewedding.app.domain.ProductReviewReportStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        var reports = status == null
                ? reportRepository.findAllByOrderByCreatedAtDesc(pageable)
                : reportRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
        if (reports.isEmpty()) return reports.map(report -> (com.utewedding.app.service.ReviewReportView) null);

        Map<UUID, ProductReviewEntity> reviewById = reviewRepository.findAllById(
                        reports.getContent().stream().map(ProductReviewReportEntity::getReviewId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(ProductReviewEntity::getId, row -> row));
        List<ProductReviewEntity> existingReviews = List.copyOf(reviewById.values());
        Map<UUID, ProductEntity> productById = productRepository.findAllById(existingReviews.stream()
                        .map(ProductReviewEntity::getProductId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(ProductEntity::getId, row -> row));
        List<UUID> userIds = java.util.stream.Stream.concat(
                reports.getContent().stream().map(ProductReviewReportEntity::getReporterId),
                existingReviews.stream().map(ProductReviewEntity::getUserId)).distinct().toList();
        Map<UUID, UserEntity> userById = userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(UserEntity::getId, row -> row));
        return reports.map(report -> {
            ProductReviewEntity review = reviewById.get(report.getReviewId());
            UserEntity reporter = userById.get(report.getReporterId());
            UserEntity reviewer = review == null ? null : userById.get(review.getUserId());
            ProductEntity product = review == null ? null : productById.get(review.getProductId());
            return new com.utewedding.app.service.ReviewReportView(report.getId(), report.getReviewId(),
                    report.getStatus(), review == null ? "Đánh giá không còn tồn tại." : review.getContent(),
                    review == null ? 0 : review.getRating(), product == null ? "Sản phẩm không còn tồn tại." : product.getName(),
                    reviewer == null ? "Khách hàng" : reviewerName(reviewer.getDisplayName()),
                    reporter == null ? "Tài khoản không còn tồn tại" : reporter.getDisplayName(),
                    reporter == null ? "" : reporter.getEmail(), report.getReason(), report.getCreatedAt());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveReport(UUID actorId, UUID reportId,
                              com.utewedding.app.domain.ProductReviewReportStatus status, RequestContext context) {
        UserRole role = userRepository.findById(actorId).map(user -> user.getRole())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        if (role != UserRole.ADMIN && role != UserRole.MANAGER && role != UserRole.SUPER_ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("Bạn không có quyền xử lý báo cáo.");
        }
        if (status != com.utewedding.app.domain.ProductReviewReportStatus.RESOLVED
                && status != com.utewedding.app.domain.ProductReviewReportStatus.DISMISSED) {
            throw new BusinessConflictException("Trạng thái báo cáo không hợp lệ.");
        }
        var report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy báo cáo."));
        if (report.getStatus() != com.utewedding.app.domain.ProductReviewReportStatus.OPEN) {
            throw new BusinessConflictException("Báo cáo đã được xử lý.");
        }
        if (status == com.utewedding.app.domain.ProductReviewReportStatus.RESOLVED
                && reviewRepository.existsById(report.getReviewId())) {
            moderate(actorId, report.getReviewId(),
                    new ModerateProductReviewRequest(ProductReviewStatus.REJECTED), context);
        }
        report.setStatus(status);
        report.setUpdatedAt(java.time.Instant.now());
        reportRepository.save(report);
        auditLog.record("REVIEW_REPORTED", actorId, "ProductReviewReport", reportId,
                java.util.Map.of("status", status.name()), context);
        attentionChanged("ProductReviewReport", reportId);
    }

    private ProductReviewEntity owned(UUID reviewId, UUID userId) {
        ProductReviewEntity review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đánh giá."));
        if (!userId.equals(review.getUserId()))
            throw new org.springframework.security.access.AccessDeniedException("Bạn không sở hữu đánh giá này.");
        return review;
    }

    private void attentionChanged(String resourceType, UUID resourceId) {
        if (events != null) events.publishEvent(
                new com.utewedding.app.event.AdminAttentionChangedEvent(resourceType, resourceId));
    }

    private void recalculate(ProductEntity product) {
        long count = reviewRepository.countByProductIdAndStatus(product.getId(), ProductReviewStatus.APPROVED);
        BigDecimal average = reviewRepository.averageRating(product.getId(), ProductReviewStatus.APPROVED);
        product.setReviewCount(Math.toIntExact(count));
        product.setAverageRating(average == null ? BigDecimal.ZERO : average.setScale(2, RoundingMode.HALF_UP));
        productRepository.save(product);
    }

    private ProductReviewResponse toResponse(ProductReviewEntity review) {
        String name = userRepository.findById(review.getUserId())
                .map(user -> reviewerName(user.getDisplayName())).orElse("Khách hàng");
        List<ProductReviewMediaResponse> media = mediaRepository.findByReviewIdOrderByCreatedAtAsc(review.getId())
                .stream().map(this::toMediaResponse).toList();
        return toResponse(review, name, media);
    }

    private Page<ProductReviewResponse> toResponsePage(Page<ProductReviewEntity> reviews) {
        if (reviews.isEmpty()) {
            return new PageImpl<>(List.of(), reviews.getPageable(), reviews.getTotalElements());
        }

        List<ProductReviewEntity> content = reviews.getContent();
        List<UUID> reviewIds = content.stream().map(ProductReviewEntity::getId).toList();
        List<UUID> userIds = content.stream().map(ProductReviewEntity::getUserId).distinct().toList();

        Map<UUID, String> reviewerNames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(user ->
                reviewerNames.put(user.getId(), reviewerName(user.getDisplayName())));

        Map<UUID, List<ProductReviewMediaResponse>> mediaByReviewId =
                mediaRepository.findByReviewIdInOrderByCreatedAtAsc(reviewIds).stream()
                        .collect(Collectors.groupingBy(ProductReviewMediaEntity::getReviewId,
                                LinkedHashMap::new,
                                Collectors.mapping(this::toMediaResponse, Collectors.toList())));

        List<ProductReviewResponse> responses = content.stream()
                .map(review -> toResponse(review,
                        reviewerNames.getOrDefault(review.getUserId(), "Khách hàng"),
                        mediaByReviewId.getOrDefault(review.getId(), List.of())))
                .toList();
        return new PageImpl<>(responses, reviews.getPageable(), reviews.getTotalElements());
    }

    private ProductReviewResponse toResponse(ProductReviewEntity review, String name,
                                              List<ProductReviewMediaResponse> media) {
        return new ProductReviewResponse(review.getId(), review.getProductId(), review.getUserId(), review.getOrderId(),
                name, review.getRating(), review.getContent(), review.getStatus(), review.getCreatedAt(),
                review.getUpdatedAt(), media);
    }

    private String reviewerName(String displayName) {
        return displayName == null || displayName.isBlank() ? "Khách hàng" : displayName;
    }

    private ProductReviewMediaResponse toMediaResponse(ProductReviewMediaEntity media) {
        return new ProductReviewMediaResponse(media.getId(), media.getSecureUrl(), media.getMimeType(),
                media.getByteSize(), media.getCreatedAt());
    }

}
