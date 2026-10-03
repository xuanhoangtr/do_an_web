package com.utewedding.app.service.impl;

import com.utewedding.app.dto.request.SubmitWishRequest;
import com.utewedding.app.dto.response.WishCreatedResponse;
import com.utewedding.app.domain.WeddingStatus;
import com.utewedding.app.domain.WishStatus;
import com.utewedding.app.domain.entity.WishEntity;
import com.utewedding.app.event.WeddingWishCreatedEvent;
import com.utewedding.app.repository.WishRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.service.PublicWishService;
import com.utewedding.app.service.TextSanitizer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicWishServiceImpl implements PublicWishService {

    private final WishRepository wishRepository;
    private final WeddingRepository weddingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TextSanitizer textSanitizer;

    /** Compatibility constructor for focused unit tests. */
    public PublicWishServiceImpl(WishRepository wishRepository,
                                 WeddingRepository weddingRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this(wishRepository, weddingRepository, eventPublisher, new TextSanitizer());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicWishServiceImpl(WishRepository wishRepository,
                                 WeddingRepository weddingRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 TextSanitizer textSanitizer) {
        this.wishRepository = wishRepository;
        this.weddingRepository = weddingRepository;
        this.eventPublisher = eventPublisher;
        this.textSanitizer = textSanitizer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WishCreatedResponse submit(UUID weddingId, SubmitWishRequest request) {
        var wedding = weddingRepository.findById(weddingId)
                .orElseThrow(() -> new com.utewedding.app.exception.ResourceNotFoundException(
                        "Không tìm thấy thiệp cưới."));
        if (wedding.getStatus() != WeddingStatus.PUBLISHED) {
            throw new com.utewedding.app.exception.ResourceNotFoundException(
                    "Thiệp cưới chưa được công khai.");
        }
        WishEntity wish = new WishEntity();
        wish.setWeddingId(weddingId);
        wish.setStatus(WishStatus.PENDING);
        wish.setSenderName(textSanitizer.trimToNull(request.senderName(), 160));
        wish.setMessage(textSanitizer.trimToNull(request.message(), 2000));
        if (wish.getSenderName() == null || wish.getMessage() == null) {
            throw new com.utewedding.app.exception.BusinessConflictException("Lời chúc không hợp lệ.");
        }
        wish.setSubmittedAt(Instant.now());
        WishEntity saved = wishRepository.save(wish);
        eventPublisher.publishEvent(new WeddingWishCreatedEvent(weddingId, saved.getId(), wedding.getOwnerId(),
                saved.getSenderName(), saved.getMessage()));
        return new WishCreatedResponse(saved.getId(), saved.getStatus().name(),
                "Lời chúc đã được gửi. Cảm ơn bạn!");
    }
}
