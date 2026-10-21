package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.SupportCategory;
import com.utewedding.app.domain.SupportPriority;
import com.utewedding.app.domain.SupportTicketKind;
import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.SupportIdempotencyEntity;
import com.utewedding.app.domain.entity.SupportIdempotencyId;
import com.utewedding.app.domain.entity.SupportMessageAttachmentEntity;
import com.utewedding.app.domain.entity.SupportMessageAttachmentId;
import com.utewedding.app.domain.entity.SupportMessageEntity;
import com.utewedding.app.domain.entity.SupportTicketEntity;
import com.utewedding.app.domain.entity.SupportTicketParticipantEntity;
import com.utewedding.app.domain.entity.SupportTicketParticipantId;
import com.utewedding.app.domain.entity.SupportTicketReadId;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.integration.ManagedMediaValidator;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.ShopSupportAttachmentRepository;
import com.utewedding.app.repository.ShopSupportMessageRepository;
import com.utewedding.app.repository.ShopSupportOwnerRepository;
import com.utewedding.app.repository.ShopSupportParticipantRepository;
import com.utewedding.app.repository.ShopSupportReadRepository;
import com.utewedding.app.repository.ShopSupportReferenceRepository;
import com.utewedding.app.repository.ShopSupportTicketRepository;
import com.utewedding.app.repository.SupportIdempotencyRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.SupportChatService.ChatAttachment;
import com.utewedding.app.service.impl.SupportChatService.ChatMessage;
import com.utewedding.app.service.impl.SupportChatService.ConversationDetail;
import com.utewedding.app.service.impl.SupportChatService.ConversationSummary;
import com.utewedding.app.service.impl.SupportChatService.MessagePage;
import com.utewedding.app.service.impl.SupportChatService.Participant;
import com.utewedding.app.service.impl.SupportChatService.SendResult;
import com.utewedding.app.service.impl.SupportChatService.SupportChatEvent;
import com.utewedding.app.service.impl.SupportChatService.TypingUser;
import com.utewedding.app.service.impl.SupportChatService.UploadedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped backend adapter for customer-to-shop conversations.
 *
 * <p>This deliberately does not delegate to the global support service: every read,
 * write, participant, attachment and typing operation resolves an active shop scope
 * and uses shop-keyed repository methods. Shop conversations have no INTERNAL lane.</p>
 */
@Service
public class ShopSupportChatService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PAGE_SIZE = 50;
    private static final int MAX_ATTACHMENTS = 5;
    private static final String CUSTOMER_VISIBILITY = "CUSTOMER";

    private final MarketplaceFeatureFlags features;
    private final ShopRepository shops;
    private final ShopSupportTicketRepository tickets;
    private final ShopSupportMessageRepository messages;
    private final ShopSupportAttachmentRepository attachments;
    private final ShopSupportParticipantRepository participants;
    private final ShopSupportOwnerRepository owners;
    private final ShopSupportReadRepository reads;
    private final ShopSupportReferenceRepository references;
    private final SupportIdempotencyRepository idempotencies;
    private final ManagedMediaObjectRepository mediaObjects;
    private final ManagedMediaService media;
    private final CloudinaryPrivateMediaProxy privateMediaProxy;
    private final ManagedMediaValidator mediaValidator;
    private final UserRepository users;
    private final VendorWorkspaceContext workspace;
    private final ShopAccessPolicy shopAccess;
    private final ApplicationEventPublisher events;
    private final SupportTypingRegistry typing;
    private final CustomerMessagesService customerMessages;

    public ShopSupportChatService(MarketplaceFeatureFlags features, ShopRepository shops,
            ShopSupportTicketRepository tickets, ShopSupportMessageRepository messages,
            ShopSupportAttachmentRepository attachments, ShopSupportParticipantRepository participants,
            ShopSupportOwnerRepository owners, ShopSupportReadRepository reads,
            ShopSupportReferenceRepository references, SupportIdempotencyRepository idempotencies,
            ManagedMediaObjectRepository mediaObjects, ManagedMediaService media,
            CloudinaryPrivateMediaProxy privateMediaProxy,
            ManagedMediaValidator mediaValidator, UserRepository users,
            VendorWorkspaceContext workspace, ShopAccessPolicy shopAccess,
            ApplicationEventPublisher events, SupportTypingRegistry typing,
            CustomerMessagesService customerMessages) {
        this.features = features;
        this.shops = shops;
        this.tickets = tickets;
        this.messages = messages;
        this.attachments = attachments;
        this.participants = participants;
        this.owners = owners;
        this.reads = reads;
        this.references = references;
        this.idempotencies = idempotencies;
        this.mediaObjects = mediaObjects;
        this.media = media;
        this.privateMediaProxy = privateMediaProxy;
        this.mediaValidator = mediaValidator;
        this.users = users;
        this.workspace = workspace;
        this.shopAccess = shopAccess;
        this.events = events;
        this.typing = typing;
        this.customerMessages = customerMessages;
    }

    @Transactional(readOnly = true)
    public Page<ConversationSummary> customerConversations(UserPrincipal actor, UUID shopId, int page) {
        UserEntity customer = requireCustomer(actor);
        int currentPage = clamp(page);
        if (!customerMessages.hasExistingContact(customer.getId(), shopId)) {
            return Page.empty(PageRequest.of(currentPage, 20));
        }
        return tickets.findByTicketKindAndShopIdAndCustomerIdOrderByLastActivityAtDesc(
                        SupportTicketKind.SHOP, shopId, customer.getId(), PageRequest.of(currentPage, 20))
                .map(ticket -> summary(ticket, customer.getId(), null));
    }

    @Transactional(readOnly = true)
    public Page<ConversationSummary> vendorConversations(UserPrincipal actor, int page) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        return tickets.findByTicketKindAndShopIdOrderByLastActivityAtDesc(
                        SupportTicketKind.SHOP, current.shopId(), PageRequest.of(clamp(page), 20))
                .map(ticket -> summary(ticket, ticket.getCustomerId(), actor.getId()));
    }

    @Transactional(readOnly = true)
    public ConversationDetail customerConversation(UserPrincipal actor, UUID shopId, UUID ticketId) {
        UserEntity customer = requireCustomer(actor);
        customerMessages.requireExistingContact(customer.getId(), shopId);
        SupportTicketEntity ticket = customerTicket(ticketId, shopId, customer.getId());
        return detail(ticket, customer.getId(), null, true);
    }

    @Transactional(readOnly = true)
    public ConversationDetail vendorConversation(UserPrincipal actor, UUID ticketId) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        SupportTicketEntity ticket = vendorTicket(ticketId, current.shopId());
        return detail(ticket, ticket.getCustomerId(), actor.getId(), false);
    }

    @Transactional(readOnly = true)
    public MessagePage customerMessages(UserPrincipal actor, UUID shopId, UUID ticketId, int page) {
        UserEntity customer = requireCustomer(actor);
        customerMessages.requireExistingContact(customer.getId(), shopId);
        customerTicket(ticketId, shopId, customer.getId());
        return messagePage(ticketId, shopId, customer.getId(), page);
    }

    @Transactional(readOnly = true)
    public MessagePage vendorMessages(UserPrincipal actor, UUID ticketId, int page) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        SupportTicketEntity ticket = vendorTicket(ticketId, current.shopId());
        return messagePage(ticketId, current.shopId(), ticket.getCustomerId(), page);
    }

    @Transactional
    public SendResult createCustomerConversation(UserPrincipal actor, UUID shopId, String idempotencyKey,
                                                  CreateConversation command) {
        requireShopChat();
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireActiveShop(shopId);
        CreateConversation clean = command == null ? new CreateConversation(null, null, null,
                null, null, null, null, null, List.of()) : command.withDefaults();
        validateReferences(clean, customer.getId(), shop.getId());
        String body = cleanBody(clean.body(), clean.attachmentMediaIds());
        String subject = requiredSubject(clean.subject(), body, clean.attachmentMediaIds());
        requireAttachmentLimit(clean.attachmentMediaIds());
        String hash = hash("SHOP_CREATE", shop.getId().toString(), customer.getId().toString(),
                clean.category().name(), subject, body, stringValue(clean.orderId()), stringValue(clean.paymentId()),
                stringValue(clean.returnCaseId()), stringValue(clean.returnDisputeId()),
                stringValue(clean.fulfillmentId()), clean.attachmentMediaIds().toString());
        SupportIdempotencyEntity prior = prior(customer.getId(), idempotencyKey, hash);
        if (prior != null) {
            SupportTicketEntity replay = tickets.findByIdAndTicketKindAndShopIdAndCustomerId(
                    prior.getTicketId(), SupportTicketKind.SHOP, shop.getId(), customer.getId())
                    .orElseThrow(ShopSupportChatService::idempotencyConflict);
            return new SendResult(replay.getId(), summary(replay, customer.getId(), null), outsideBusinessHours());
        }

        customerMessages.lockForNewConversation(customer.getId(), shop.getId());
        prior = prior(customer.getId(), idempotencyKey, hash);
        if (prior != null) {
            SupportTicketEntity replay = tickets.findByIdAndTicketKindAndShopIdAndCustomerId(
                    prior.getTicketId(), SupportTicketKind.SHOP, shop.getId(), customer.getId())
                    .orElseThrow(ShopSupportChatService::idempotencyConflict);
            return new SendResult(replay.getId(), summary(replay, customer.getId(), null), outsideBusinessHours());
        }
        customerMessages.requireNewConversationAllowed(customer.getId(), shop.getId());
        Set<UUID> ownerIds = requireActiveOwnerIds(shop.getId());
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setTicketKind(SupportTicketKind.SHOP);
        ticket.setShopId(shop.getId());
        ticket.setCustomerId(customer.getId());
        ticket.setCategory(Objects.requireNonNull(clean.category(), "category"));
        ticket.setPriority(SupportPriority.NORMAL);
        ticket.setStatus(SupportTicketStatus.OPEN);
        ticket.setSubject(subject);
        ticket.setOrderId(clean.orderId());
        ticket.setPaymentId(clean.paymentId());
        ticket.setReturnCaseId(clean.returnCaseId());
        ticket.setReturnDisputeId(clean.returnDisputeId());
        ticket.setFulfillmentId(clean.fulfillmentId());
        ticket = tickets.saveAndFlush(ticket);
        customerMessages.linkNewConversation(customer.getId(), shop.getId(), ticket.getId());

        SupportMessageEntity message = createMessage(ticket, customer.getId(), body, shop.getId());
        attach(shop.getId(), customer.getId(), message.getId(), clean.attachmentMediaIds());
        addParticipant(shop.getId(), ticket.getId(), customer.getId());
        Set<UUID> recipients = ownerIds;
        for (UUID ownerId : recipients) addParticipant(shop.getId(), ticket.getId(), ownerId);
        saveIdempotency(customer.getId(), idempotencyKey, hash, ticket.getId(), message.getId());
        publishMessageChanged(ticket.getId(), customer.getId(), recipients, customer.getId());
        return new SendResult(ticket.getId(), summary(ticket, customer.getId(), null), outsideBusinessHours());
    }

    @Transactional
    public SendResult customerReply(UserPrincipal actor, UUID shopId, UUID ticketId,
                                    String idempotencyKey, Reply command) {
        requireShopChat();
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireActiveShop(shopId);
        SupportTicketEntity ticket = tickets.findCustomerTicketForUpdate(ticketId, SupportTicketKind.SHOP,
                        shop.getId(), customer.getId())
                .orElseThrow(ShopSupportChatService::ticketNotFound);
        Reply clean = command == null ? new Reply(null, List.of()) : command.withDefaults();
        String body = cleanBody(clean.body(), clean.attachmentMediaIds());
        requireAttachmentLimit(clean.attachmentMediaIds());
        String hash = hash("SHOP_REPLY", "CUSTOMER", shop.getId().toString(), ticketId.toString(),
                body, clean.attachmentMediaIds().toString());
        SupportIdempotencyEntity prior = prior(customer.getId(), idempotencyKey, hash);
        if (prior != null) {
            if (!ticketId.equals(prior.getTicketId())) throw idempotencyConflict();
            return new SendResult(ticketId, summary(ticket, customer.getId(), null), outsideBusinessHours());
        }
        ensureCustomerCanReply(ticket);
        Set<UUID> ownerIds = requireActiveOwnerIds(shop.getId());
        SupportMessageEntity message = createMessage(ticket, customer.getId(), body, shop.getId());
        attach(shop.getId(), customer.getId(), message.getId(), clean.attachmentMediaIds());
        if (ticket.getStatus() == SupportTicketStatus.WAITING_CUSTOMER) {
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        } else if (ticket.getStatus() == SupportTicketStatus.RESOLVED) {
            ticket.setStatus(SupportTicketStatus.OPEN);
            ticket.setResolvedAt(null);
        }
        ticket.touch();
        saveIdempotency(customer.getId(), idempotencyKey, hash, ticketId, message.getId());
        publishMessageChanged(ticketId, customer.getId(), ownerIds, customer.getId());
        return new SendResult(ticketId, summary(ticket, customer.getId(), null), outsideBusinessHours());
    }

    @Transactional
    public SendResult vendorReply(UserPrincipal actor, UUID ticketId, String idempotencyKey, Reply command) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, true);
        SupportTicketEntity ticket = tickets.findShopTicketForUpdate(ticketId, SupportTicketKind.SHOP,
                        current.shopId())
                .orElseThrow(ShopSupportChatService::ticketNotFound);
        Reply clean = command == null ? new Reply(null, List.of()) : command.withDefaults();
        String body = cleanBody(clean.body(), clean.attachmentMediaIds());
        requireAttachmentLimit(clean.attachmentMediaIds());
        String hash = hash("SHOP_REPLY", "VENDOR", current.shopId().toString(), ticketId.toString(),
                body, clean.attachmentMediaIds().toString());
        SupportIdempotencyEntity prior = prior(actor.getId(), idempotencyKey, hash);
        if (prior != null) {
            if (!ticketId.equals(prior.getTicketId())) throw idempotencyConflict();
            return new SendResult(ticketId, summary(ticket, ticket.getCustomerId(), actor.getId()),
                    outsideBusinessHours());
        }
        ensureVendorCanReply(ticket);
        SupportMessageEntity message = createMessage(ticket, actor.getId(), body, current.shopId());
        attach(current.shopId(), actor.getId(), message.getId(), clean.attachmentMediaIds());
        addParticipant(current.shopId(), ticketId, actor.getId());
        if (ticket.getStatus() != SupportTicketStatus.WAITING_CUSTOMER) {
            ticket.setStatus(SupportTicketStatus.WAITING_CUSTOMER);
        }
        ticket.touch();
        saveIdempotency(actor.getId(), idempotencyKey, hash, ticketId, message.getId());
        Set<UUID> recipients = new LinkedHashSet<>(activeOwnerIds(current.shopId()));
        recipients.add(ticket.getCustomerId());
        publishMessageChanged(ticketId, actor.getId(), recipients, ticket.getCustomerId());
        return new SendResult(ticketId, summary(ticket, ticket.getCustomerId(), actor.getId()),
                outsideBusinessHours());
    }

    @Transactional
    public void markCustomerRead(UserPrincipal actor, UUID shopId, UUID ticketId) {
        UserEntity customer = requireCustomer(actor);
        customerMessages.requireExistingContact(customer.getId(), shopId);
        customerTicket(ticketId, shopId, customer.getId());
        reads.markShopRead(ticketId, customer.getId(), shopId, Instant.now());
    }

    @Transactional
    public void markVendorRead(UserPrincipal actor, UUID ticketId) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, true);
        vendorTicket(ticketId, current.shopId());
        reads.markShopRead(ticketId, actor.getId(), current.shopId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public long customerUnreadCount(UserPrincipal actor, UUID shopId) {
        UserEntity customer = requireCustomer(actor);
        customerMessages.requireExistingContact(customer.getId(), shopId);
        return reads.countCustomerUnread(shopId, customer.getId());
    }

    @Transactional(readOnly = true)
    public long vendorUnreadCount(UserPrincipal actor) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        return reads.countVendorUnread(current.shopId(), actor.getId());
    }

    public List<UploadedImage> uploadCustomerImages(UserPrincipal actor, UUID shopId,
                                                      List<MultipartFile> files) {
        requireShopChat();
        UserEntity customer = requireCustomer(actor);
        requireActiveShop(shopId);
        return uploadImages(customer.getId(), files);
    }

    @Transactional
    public List<UploadedImage> uploadVendorImages(UserPrincipal actor, List<MultipartFile> files) {
        requireShopChat();
        requireVendorWorkspace(actor, true);
        return uploadImages(actor.getId(), files);
    }

    @Transactional(readOnly = true)
    public PrivateAttachmentDownload customerAttachment(UserPrincipal actor, UUID shopId,
                                                         UUID ticketId, UUID mediaId) {
        UserEntity customer = requireCustomer(actor);
        customerMessages.requireExistingContact(customer.getId(), shopId);
        customerTicket(ticketId, shopId, customer.getId());
        return privateAttachment(shopId, ticketId, mediaId);
    }

    @Transactional(readOnly = true)
    public PrivateAttachmentDownload vendorAttachment(UserPrincipal actor, UUID ticketId, UUID mediaId) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        vendorTicket(ticketId, current.shopId());
        return privateAttachment(current.shopId(), ticketId, mediaId);
    }

    @Transactional(readOnly = true)
    public void requireCustomerTypingAccess(UserPrincipal actor, UUID shopId, UUID ticketId) {
        requireShopChat();
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireActiveShop(shopId);
        customerTicket(ticketId, shop.getId(), customer.getId());
    }

    @Transactional
    public void requireVendorTypingAccess(UserPrincipal actor, UUID ticketId) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, true);
        vendorTicket(ticketId, current.shopId());
    }

    @Transactional
    public void customerTyping(UserPrincipal actor, UUID shopId, UUID ticketId, boolean active) {
        requireCustomerTypingAccess(actor, shopId, ticketId);
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireActiveShop(shopId);
        updateTyping(ticketId, customer.getId(), customer.getDisplayName(), active,
                activeOwnerIds(shop.getId()), customer.getId());
    }

    @Transactional
    public void vendorTyping(UserPrincipal actor, UUID ticketId, boolean active) {
        requireVendorTypingAccess(actor, ticketId);
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, true);
        SupportTicketEntity ticket = vendorTicket(ticketId, current.shopId());
        UserEntity vendor = users.findById(actor.getId()).orElseThrow(ShopSupportChatService::ticketNotFound);
        Set<UUID> recipients = new LinkedHashSet<>(activeOwnerIds(current.shopId()));
        recipients.add(ticket.getCustomerId());
        updateTyping(ticketId, vendor.getId(), vendor.getDisplayName(), active, recipients, ticket.getCustomerId());
    }

    @Transactional(readOnly = true)
    public List<TypingUser> activeCustomerTyping(UserPrincipal actor, UUID shopId, UUID ticketId) {
        requireShopChat();
        UserEntity customer = requireCustomer(actor);
        ShopEntity shop = requireActiveShop(shopId);
        SupportTicketEntity ticket = customerTicket(ticketId, shop.getId(), customer.getId());
        return activeShopTyping(ticket, actor.getId());
    }

    @Transactional(readOnly = true)
    public List<TypingUser> activeVendorTyping(UserPrincipal actor, UUID ticketId) {
        requireShopChat();
        VendorWorkspaceContext.Workspace current = requireVendorWorkspace(actor, false);
        SupportTicketEntity ticket = vendorTicket(ticketId, current.shopId());
        return activeShopTyping(ticket, actor.getId());
    }

    private List<TypingUser> activeShopTyping(SupportTicketEntity ticket, UUID excludedUserId) {
        Set<UUID> visibleActors = new LinkedHashSet<>(activeOwnerIds(ticket.getShopId()));
        visibleActors.add(ticket.getCustomerId());
        return typing.active(ticket.getId(), excludedUserId).stream()
                .filter(user -> visibleActors.contains(user.id()))
                .toList();
    }

    private MessagePage messagePage(UUID ticketId, UUID shopId, UUID customerId, int page) {
        Page<SupportMessageEntity> found = messages.findByTicketIdAndShopIdAndVisibilityOrderByCreatedAtDescIdDesc(
                ticketId, shopId, CUSTOMER_VISIBILITY, PageRequest.of(clamp(page), PAGE_SIZE));
        List<SupportMessageEntity> rows = new ArrayList<>(found.getContent());
        java.util.Collections.reverse(rows);
        List<UUID> messageIds = rows.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<UUID>> mediaByMessage = new HashMap<>();
        if (!messageIds.isEmpty()) {
            attachments.findByShopIdAndMessageIds(shopId, messageIds).forEach(item -> mediaByMessage
                    .computeIfAbsent(item.getId().getMessageId(), ignored -> new ArrayList<>())
                    .add(item.getId().getManagedMediaId()));
        }
        Set<UUID> authorIds = rows.stream().map(SupportMessageEntity::getAuthorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserEntity> authorMap = users.findAllById(authorIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, user -> user));
        String root = "/shop/chat/conversations/";
        List<ChatMessage> output = rows.stream().map(message -> {
            UserEntity author = authorMap.get(message.getAuthorId());
            List<ChatAttachment> attached = mediaByMessage.getOrDefault(message.getId(), List.of()).stream()
                    .map(mediaId -> new ChatAttachment(mediaId,
                            root + ticketId + "/attachments/" + mediaId)).toList();
            return new ChatMessage(message.getId(), message.getAuthorId(),
                    author == null || author.getDisplayName() == null ? "Tài khoản đã xóa" : author.getDisplayName(),
                    author == null ? "UNKNOWN" : author.getRole().name(), CUSTOMER_VISIBILITY,
                    message.getBody(), attached, message.getCreatedAt(), customerId.equals(message.getAuthorId()));
        }).toList();
        return new MessagePage(output, found.getNumber(), found.hasNext(), found.getTotalElements());
    }

    private ConversationSummary summary(SupportTicketEntity ticket, UUID customerId, UUID vendorActorId) {
        SupportMessageEntity last = messages.findFirstByTicketIdAndShopIdAndVisibilityOrderByCreatedAtDescIdDesc(
                ticket.getId(), ticket.getShopId(), CUSTOMER_VISIBILITY).orElse(null);
        String preview = last == null ? "" : last.getBody();
        if (last != null && preview.isBlank() && attachments.existsByShopIdAndIdMessageId(
                ticket.getShopId(), last.getId())) {
            preview = "Đã gửi ảnh";
        }
        if (preview.length() > 100) preview = preview.substring(0, 100) + "…";
        long unread = vendorActorId == null
                ? reads.countCustomerUnreadForTicket(ticket.getId(), ticket.getShopId(), customerId)
                : reads.countVendorUnreadForTicket(ticket.getId(), ticket.getShopId(), customerId, vendorActorId);
        String replyState = replyState(ticket, last);
        return new ConversationSummary(ticket.getId(), ticket.getTicketNumber(), ticket.getSubject(),
                ticket.getCategory().name(), ticket.getStatus().name(), ticket.getPriority().name(),
                ticket.getOrderId(), ticket.getReturnCaseId(), ticket.getFulfillmentId(), null, null,
                ticket.getLastActivityAt(), preview, unread, replyState);
    }

    private ConversationDetail detail(SupportTicketEntity ticket, UUID customerId, UUID vendorActorId,
                                      boolean customerView) {
        Set<UUID> visibleParticipantIds = new LinkedHashSet<>(activeOwnerIds(ticket.getShopId()));
        visibleParticipantIds.add(ticket.getCustomerId());
        List<Participant> listed = participants.findByShopIdAndIdTicketIdOrderByJoinedAtAsc(
                        ticket.getShopId(), ticket.getId()).stream()
                .map(SupportTicketParticipantEntity::getId)
                .map(SupportTicketParticipantId::getUserId).distinct()
                // Participant rows are historical. Only the conversation customer and current
                // shop owners are part of the shop-facing participant view.
                .filter(visibleParticipantIds::contains)
                .map(id -> users.findById(id)
                        .map(user -> new Participant(id, user.getDisplayName(), user.getRole().name()))
                        .orElse(null))
                .filter(Objects::nonNull).toList();
        UserEntity customer = users.findById(customerId).orElse(null);
        long unread = customerView
                ? reads.countCustomerUnreadForTicket(ticket.getId(), ticket.getShopId(), customerId)
                : reads.countVendorUnreadForTicket(ticket.getId(), ticket.getShopId(), customerId, vendorActorId);
        boolean canSend = customerView ? customerCanSend(ticket) : vendorCanSend(ticket);
        return new ConversationDetail(ticket.getId(), ticket.getTicketNumber(), ticket.getSubject(),
                ticket.getCategory().name(), ticket.getStatus().name(), ticket.getPriority().name(),
                customerId, customer == null ? "Khách hàng" : customer.getDisplayName(), ticket.getOrderId(),
                ticket.getReturnCaseId(), ticket.getFulfillmentId(), null, null, listed,
                vendorActorId != null && participants.existsByShopIdAndIdTicketIdAndIdUserId(
                        ticket.getShopId(), ticket.getId(), vendorActorId), unread, outsideBusinessHours(), canSend);
    }

    private String replyState(SupportTicketEntity ticket, SupportMessageEntity latest) {
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED || ticket.getStatus() == SupportTicketStatus.CLOSED) {
            return SupportService.ReplyState.NONE.name();
        }
        if (latest == null) return SupportService.ReplyState.NEEDS_STAFF.name();
        return ticket.getCustomerId().equals(latest.getAuthorId())
                ? SupportService.ReplyState.CUSTOMER_REPLIED.name()
                : SupportService.ReplyState.WAITING_CUSTOMER.name();
    }

    private SupportMessageEntity createMessage(SupportTicketEntity ticket, UUID authorId, String body, UUID shopId) {
        return createMessage(ticket, authorId, body, shopId, ticket.getId());
    }

    private SupportMessageEntity createMessage(SupportTicketEntity ticket, UUID authorId, String body,
                                               UUID shopId, UUID ticketId) {
        SupportMessageEntity message = new SupportMessageEntity();
        message.setTicketId(ticketId);
        message.setShopId(shopId);
        message.setAuthorId(authorId);
        message.setVisibility(CUSTOMER_VISIBILITY);
        message.setBody(body);
        return messages.saveAndFlush(message);
    }

    private void attach(UUID shopId, UUID actorId, UUID messageId, List<UUID> mediaIds) {
        for (UUID mediaId : new LinkedHashSet<>(mediaIds)) {
            ManagedMediaObjectEntity item = mediaObjects.findById(mediaId)
                    .orElseThrow(() -> new BusinessConflictException("Không tìm thấy attachment."));
            if (!actorId.equals(item.getOwnerId()) || item.getStatus() != ManagedMediaStatus.READY
                    || !"SUPPORT_ATTACHMENT".equals(item.getPurpose())
                    || !"authenticated".equals(item.getDeliveryType())) {
                throw new AccessDeniedException("Attachment không thuộc tài khoản hoặc không hợp lệ.");
            }
            attachments.save(new SupportMessageAttachmentEntity(
                    new SupportMessageAttachmentId(messageId, mediaId), shopId));
        }
    }

    private PrivateAttachmentDownload privateAttachment(UUID shopId, UUID ticketId, UUID mediaId) {
        List<SupportMessageEntity> visible = messages.findByTicketIdAndShopIdAndVisibilityOrderByCreatedAtAscIdAsc(
                ticketId, shopId, CUSTOMER_VISIBILITY);
        boolean linked = visible.stream().anyMatch(message ->
                attachments.existsByShopIdAndIdMessageIdAndIdManagedMediaId(shopId, message.getId(), mediaId));
        if (!linked) throw new AccessDeniedException("Media không thuộc cuộc trò chuyện này.");
        ManagedMediaObjectEntity attachment = mediaObjects.findById(mediaId)
                .filter(item -> "SUPPORT_ATTACHMENT".equals(item.getPurpose())
                        && item.getStatus() == ManagedMediaStatus.READY
                        && "authenticated".equals(item.getDeliveryType())
                        && item.getShopId() == null && item.getApplicationId() == null)
                .orElseThrow(() -> new AccessDeniedException("Media không thuộc cuộc trò chuyện này."));
        CloudinaryPrivateMediaProxy.OpenedDownload stream = privateMediaProxy.open(attachment);
        String extension = "image/png".equals(attachment.getDetectedMime()) ? "png" : "jpg";
        return new PrivateAttachmentDownload(stream, "chat-attachment-" + attachment.getId() + "." + extension);
    }

    private List<UploadedImage> uploadImages(UUID actorId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        if (files.size() > MAX_ATTACHMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi tin nhắn đính kèm tối đa 5 ảnh.");
        }
        List<UploadedImage> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            var metadata = mediaValidator.validate(file);
            if (!Set.of("image/jpeg", "image/png").contains(metadata.mimeType())) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Chỉ chấp nhận ảnh JPEG hoặc PNG.");
            }
            ManagedMediaObjectEntity saved = media.upload(file, actorId, "SUPPORT_ATTACHMENT", Duration.ofDays(365));
            uploaded.add(new UploadedImage(saved.getId(), metadata.mimeType(), file.getOriginalFilename()));
        }
        return List.copyOf(uploaded);
    }

    private void validateReferences(CreateConversation command, UUID customerId, UUID shopId) {
        if (command.category() == null) throw new BusinessConflictException("Cần chọn loại hỗ trợ.");
        denyIf(command.orderId() != null && !references.orderBelongsTo(command.orderId(), customerId, shopId), command.orderId(),
                "Đơn hàng không thuộc khách hàng và cửa hàng này.");
        denyIf(command.paymentId() != null && !references.paymentBelongsTo(command.paymentId(), customerId, shopId), command.paymentId(),
                "Thanh toán không thuộc khách hàng và cửa hàng này.");
        denyIf(command.returnCaseId() != null && !references.returnCaseBelongsTo(command.returnCaseId(), customerId, shopId), command.returnCaseId(),
                "Yêu cầu trả hàng không thuộc khách hàng và cửa hàng này.");
        denyIf(command.returnDisputeId() != null && !references.returnDisputeBelongsTo(command.returnDisputeId(), customerId, shopId),
                command.returnDisputeId(), "Tranh chấp không thuộc khách hàng và cửa hàng này.");
        denyIf(command.fulfillmentId() != null && !references.fulfillmentBelongsTo(command.fulfillmentId(), customerId, shopId),
                command.fulfillmentId(), "Đợt giao hàng không thuộc khách hàng và cửa hàng này.");
    }

    private static void denyIf(boolean invalid, UUID id, String message) {
        if (id != null && invalid) throw new AccessDeniedException(message);
    }

    private void ensureCustomerCanReply(SupportTicketEntity ticket) {
        if (ticket.getStatus() == SupportTicketStatus.CLOSED) throw new BusinessConflictException("Cuộc trò chuyện đã đóng.");
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED && !customerCanReply(ticket)) {
            throw new BusinessConflictException("Cuộc trò chuyện đã hết thời hạn mở lại.");
        }
    }

    private static boolean customerCanReply(SupportTicketEntity ticket) {
        if (ticket == null || ticket.getStatus() == SupportTicketStatus.CLOSED) return false;
        if (ticket.getStatus() != SupportTicketStatus.RESOLVED) return true;
        boolean withinWindow = ticket.getResolvedAt() != null
                && !ticket.getResolvedAt().isBefore(Instant.now().minus(Duration.ofDays(7)));
        return withinWindow && ticket.getStatus().canTransitionTo(SupportTicketStatus.OPEN, withinWindow);
    }

    private boolean customerCanSend(SupportTicketEntity ticket) {
        return customerCanReply(ticket) && activeShopWithOwner(ticket.getShopId());
    }

    private static boolean vendorCanReply(SupportTicketEntity ticket) {
        return ticket != null && ticket.getStatus() != SupportTicketStatus.CLOSED
                && ticket.getStatus() != SupportTicketStatus.RESOLVED;
    }

    private boolean vendorCanSend(SupportTicketEntity ticket) {
        return vendorCanReply(ticket) && activeShop(ticket == null ? null : ticket.getShopId());
    }

    private boolean activeShopWithOwner(UUID shopId) {
        return activeShop(shopId) && !activeOwnerIds(shopId).isEmpty();
    }

    private boolean activeShop(UUID shopId) {
        ShopEntity shop = shopId == null ? null : shops.findById(shopId).orElse(null);
        return shop != null && shop.getKind() == ShopKind.VENDOR && shop.getStatus() == ShopStatus.ACTIVE;
    }

    private static void ensureVendorCanReply(SupportTicketEntity ticket) {
        if (!vendorCanReply(ticket)) {
            throw new BusinessConflictException("Cuộc trò chuyện đã được xử lý và không nhận phản hồi mới.");
        }
    }

    private void addParticipant(UUID shopId, UUID ticketId, UUID userId) {
        if (!participants.existsByShopIdAndIdTicketIdAndIdUserId(shopId, ticketId, userId)) {
            try {
                participants.saveAndFlush(new SupportTicketParticipantEntity(
                        new SupportTicketParticipantId(ticketId, userId), shopId));
            } catch (DataIntegrityViolationException ignored) {
                // Concurrent sends can add the same tenant-scoped participant.
            }
        }
    }

    private Set<UUID> activeOwnerIds(UUID shopId) {
        List<UUID> ids = owners.findActiveOwnerIds(shopId);
        return (ids == null ? java.util.stream.Stream.<UUID>empty() : ids.stream()).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> requireActiveOwnerIds(UUID shopId) {
        Set<UUID> activeOwners = activeOwnerIds(shopId);
        if (activeOwners.isEmpty()) throw new BusinessConflictException("Cửa hàng hiện chưa có người nhận tin nhắn.");
        return activeOwners;
    }

    private static void requireAttachmentLimit(List<UUID> mediaIds) {
        if (mediaIds != null && mediaIds.size() > MAX_ATTACHMENTS) {
            throw new BusinessConflictException("Mỗi tin nhắn đính kèm tối đa 5 ảnh.");
        }
    }

    private void publishMessageChanged(UUID ticketId, UUID actorId, Collection<UUID> recipients, UUID customerId) {
        Set<UUID> scoped = new LinkedHashSet<>();
        if (recipients != null) scoped.addAll(recipients);
        if (customerId != null) scoped.add(customerId);
        scoped.add(actorId);
        scoped.remove(null);
        events.publishEvent(new SupportChatEvent(ticketId, "MESSAGE_CHANGED", actorId, null, false, scoped));
    }

    private void updateTyping(UUID ticketId, UUID actorId, String actorName, boolean active,
                              Collection<UUID> recipients, UUID customerId) {
        if (active) typing.mark(ticketId, actorId, actorName); else typing.clear(ticketId, actorId);
        Set<UUID> scoped = new LinkedHashSet<>();
        if (recipients != null) scoped.addAll(recipients);
        if (customerId != null) scoped.add(customerId);
        scoped.remove(actorId);
        scoped.remove(null);
        events.publishEvent(new SupportChatEvent(ticketId, "TYPING", actorId, actorName, active, scoped));
    }

    private UserEntity requireCustomer(UserPrincipal actor) {
        requireAuthenticatedCustomerPrincipal(actor);
        UserEntity user = users.findById(actor.getId())
                .orElseThrow(() -> new AccessDeniedException("Đăng nhập để chat với cửa hàng."));
        if (user.getStatus() != UserStatus.ACTIVE
                || (user.getRole() != UserRole.USER && user.getRole() != UserRole.CUSTOMER)) {
            throw new AccessDeniedException("Chỉ tài khoản khách đang hoạt động mới được chat.");
        }
        return user;
    }

    private VendorWorkspaceContext.Workspace requireVendorWorkspace(UserPrincipal actor, boolean mutation) {
        requireAuthenticatedCustomerPrincipal(actor);
        VendorWorkspaceContext.Workspace current = workspace.requireWorkspace(actor);
        if (mutation) shopAccess.requireMutation(actor, current.shopId());
        else shopAccess.requireRead(actor, current.shopId());
        return current;
    }

    private static void requireAuthenticatedCustomerPrincipal(UserPrincipal actor) {
        if (actor == null || actor.getId() == null || !actor.isEnabled()
                || (actor.getRole() != AppRole.USER && actor.getRole() != AppRole.CUSTOMER)) {
            throw new AccessDeniedException("Đăng nhập bằng tài khoản khách để tiếp tục.");
        }
    }

    private ShopEntity requireActiveShop(UUID shopId) {
        ShopEntity shop = shopId == null ? null : shops.findById(shopId).orElse(null);
        if (shop == null || shop.getKind() != ShopKind.VENDOR || shop.getStatus() != ShopStatus.ACTIVE) {
            throw ticketNotFound();
        }
        return shop;
    }

    private SupportTicketEntity customerTicket(UUID ticketId, UUID shopId, UUID customerId) {
        return tickets.findByIdAndTicketKindAndShopIdAndCustomerId(
                        ticketId, SupportTicketKind.SHOP, shopId, customerId)
                .orElseThrow(ShopSupportChatService::ticketNotFound);
    }

    private SupportTicketEntity vendorTicket(UUID ticketId, UUID shopId) {
        return tickets.findByIdAndTicketKindAndShopId(ticketId, SupportTicketKind.SHOP, shopId)
                .orElseThrow(ShopSupportChatService::ticketNotFound);
    }

    private void requireShopChat() {
        if (features == null || !features.isEnabled(Feature.SHOP_CHAT)
                || !features.isEnabled(Feature.VENDOR_MANAGEMENT)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private SupportIdempotencyEntity prior(UUID actorId, String key, String requestHash) {
        String normalized = normalizedKey(key);
        SupportIdempotencyEntity prior = idempotencies.findById(new SupportIdempotencyId(actorId, normalized))
                .orElse(null);
        if (prior != null && !prior.getRequestHash().equals(requestHash)) throw idempotencyConflict();
        return prior;
    }

    private void saveIdempotency(UUID actorId, String key, String requestHash, UUID ticketId, UUID messageId) {
        try {
            idempotencies.saveAndFlush(new SupportIdempotencyEntity(
                    new SupportIdempotencyId(actorId, normalizedKey(key)), requestHash, ticketId, messageId));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Yêu cầu đang được xử lý. Vui lòng tải lại cuộc trò chuyện.");
        }
    }

    private static String normalizedKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 190) {
            throw new BusinessConflictException("Idempotency-Key là bắt buộc.");
        }
        return key.trim();
    }

    private static String cleanBody(String body, List<UUID> attachments) {
        if (body == null || body.isBlank()) {
            if (attachments == null || attachments.isEmpty()) {
                throw new BusinessConflictException("Hãy nhập tin nhắn hoặc đính kèm ảnh.");
            }
            return "";
        }
        String clean = body.trim();
        if (clean.length() > 5000) throw new BusinessConflictException("Nội dung quá dài.");
        return clean;
    }

    private static String requiredSubject(String provided, String body, List<UUID> attachments) {
        String value = provided == null || provided.isBlank()
                ? (body.isBlank() ? "Hình ảnh hỗ trợ cửa hàng" : body.replaceAll("\\s+", " "))
                : provided.trim().replaceAll("\\s+", " ");
        if (value.isBlank() && (attachments == null || attachments.isEmpty())) {
            throw new BusinessConflictException("Tiêu đề là bắt buộc.");
        }
        return value.substring(0, Math.min(value.length(), 200));
    }

    private static String hash(String... parts) {
        try {
            StringBuilder canonical = new StringBuilder();
            for (String part : parts) {
                if (part == null) {
                    canonical.append("-1:");
                } else {
                    canonical.append(part.length()).append(':').append(part);
                }
            }
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String stringValue(UUID value) {
        return value == null ? null : value.toString();
    }

    private static BusinessConflictException idempotencyConflict() {
        return new BusinessConflictException("Idempotency-Key đã được dùng cho yêu cầu khác.");
    }

    private static ResourceNotFoundException ticketNotFound() {
        return new ResourceNotFoundException("Không tìm thấy cuộc trò chuyện.");
    }

    private static int clamp(int page) { return Math.max(0, Math.min(page, 10000)); }

    private static boolean outsideBusinessHours() {
        ZonedDateTime now = Instant.now().atZone(VIETNAM);
        return now.getDayOfWeek().getValue() > 5 || now.toLocalTime().isBefore(LocalTime.of(8, 0))
                || !now.toLocalTime().isBefore(LocalTime.of(17, 0));
    }

    public record CreateConversation(SupportCategory category, String subject, String body,
            UUID orderId, UUID paymentId, UUID returnCaseId, UUID returnDisputeId, UUID fulfillmentId,
            List<UUID> attachmentMediaIds) {
        CreateConversation withDefaults() {
            return new CreateConversation(category, subject, body, orderId, paymentId, returnCaseId,
                    returnDisputeId, fulfillmentId, attachmentMediaIds == null ? List.of()
                    : List.copyOf(new LinkedHashSet<>(attachmentMediaIds)));
        }
    }

    public record Reply(String body, List<UUID> attachmentMediaIds) {
        Reply withDefaults() {
            return new Reply(body, attachmentMediaIds == null ? List.of()
                    : List.copyOf(new LinkedHashSet<>(attachmentMediaIds)));
        }
    }

    public record PrivateAttachmentDownload(CloudinaryPrivateMediaProxy.OpenedDownload stream, String fileName) { }
}
