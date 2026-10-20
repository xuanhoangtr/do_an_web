package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.SupportCategory;
import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.exception.SupportChatParticipationRequiredException;
import com.utewedding.app.integration.ManagedMediaValidator;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.SupportService.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Browser chat adapter over the existing support ticket, message, media and outbox records. */
@Service
public class SupportChatService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<UserRole> STAFF_ROLES = Set.of(UserRole.MANAGER, UserRole.ADMIN, UserRole.SUPER_ADMIN);
    private final SupportService support;
    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final SupportMessageAttachmentRepository attachments;
    private final SupportTicketParticipantRepository participants;
    private final SupportTicketReadRepository reads;
    private final UserRepository users;
    private final ManagedMediaService media;
    private final ManagedMediaValidator mediaValidator;
    private final ApplicationEventPublisher events;
    private final SupportTypingRegistry typing;

    public SupportChatService(SupportService support, SupportTicketRepository tickets,
                              SupportMessageRepository messages,
                              SupportMessageAttachmentRepository attachments,
                              SupportTicketParticipantRepository participants,
                              SupportTicketReadRepository reads, UserRepository users,
                              ManagedMediaService media, ManagedMediaValidator mediaValidator,
                              ApplicationEventPublisher events, SupportTypingRegistry typing) {
        this.support = support;
        this.tickets = tickets;
        this.messages = messages;
        this.attachments = attachments;
        this.participants = participants;
        this.reads = reads;
        this.users = users;
        this.media = media;
        this.mediaValidator = mediaValidator;
        this.events = events;
        this.typing = typing;
    }

    @Transactional(readOnly = true)
    public Page<ConversationSummary> customerConversations(UUID actor, int page) {
        requireCustomer(actor);
        Page<TicketView> result = support.search(actor, false, null, null, null, null, null, clamp(page));
        return result.map(ticket -> summary(ticket, false, actor));
    }

    @Transactional(readOnly = true)
    public Page<ConversationSummary> staffConversations(UUID actor, int page, String query,
            SupportTicketStatus status, boolean mine, boolean unassigned, boolean needsReply) {
        requireStaff(actor);
        Page<TicketView> result = support.staffSearch(actor, status, null,
                mine ? actor : null, null, query, unassigned, needsReply, clamp(page));
        return result.map(ticket -> summary(ticket, true, actor));
    }

    @Transactional(readOnly = true)
    public ConversationDetail customerConversation(UUID actor, UUID ticketId) {
        requireCustomer(actor);
        TicketView ticket = support.customerView(actor, ticketId);
        return detail(ticket, false, actor);
    }

    @Transactional(readOnly = true)
    public ConversationDetail staffConversation(UUID actor, UUID ticketId) {
        requireStaff(actor);
        TicketView ticket = support.staffView(actor, ticketId);
        return detail(ticket, true, actor);
    }

    @Transactional(readOnly = true)
    public MessagePage customerMessages(UUID actor, UUID ticketId, int page) {
        requireCustomer(actor);
        support.customerView(actor, ticketId);
        return messagePage(ticketId, actor, false, page);
    }

    @Transactional(readOnly = true)
    public MessagePage staffMessages(UUID actor, UUID ticketId, int page) {
        requireStaff(actor);
        support.staffView(actor, ticketId);
        return messagePage(ticketId, actor, true, page);
    }

    @Transactional
    public SendResult customerSend(UUID actor, UUID ticketId, String key, CustomerMessage command,
                                   RequestContext context) {
        requireCustomer(actor);
        CustomerMessage clean = command == null ? new CustomerMessage(null, List.of(), null, null, null)
                : command.withDefaults();
        TicketView result;
        if (ticketId == null) {
            SupportCategory category = clean.returnCaseId() != null ? SupportCategory.RETURN
                    : clean.orderId() != null ? SupportCategory.ORDER : SupportCategory.GENERAL;
            String subject = subjectFrom(clean.body(), clean.attachmentMediaIds());
            result = support.create(actor, key, new CreateTicket(category, subject, clean.body(),
                    clean.orderId(), clean.returnCaseId(), null, clean.fulfillmentId(), clean.attachmentMediaIds()), context);
        } else {
            result = support.reply(actor, ticketId, key,
                    new Reply(clean.body(), false, clean.attachmentMediaIds()), false, context);
        }
        publishMessageChanged(result.id(), actor, false, result.customerId());
        return new SendResult(result.id(), summary(result, false, actor), outsideBusinessHours());
    }

    @Transactional
    public SendResult staffSend(UUID actor, UUID ticketId, String key, StaffMessage command,
                                RequestContext context) {
        UserEntity staff = requireStaff(actor);
        SupportTicketEntity ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội thoại."));
        boolean participant = participants.existsByIdTicketIdAndIdUserId(ticketId, actor);
        UUID assignee = ticket.getAssignedAgentId();
        if (assignee != null && !assignee.equals(actor) && !participant) {
            UserEntity assignedUser = users.findById(assignee).orElse(null);
            String name = assignedUser == null || assignedUser.getDisplayName() == null
                    ? "nhân viên khác" : assignedUser.getDisplayName();
            throw new SupportChatParticipationRequiredException(name);
        }
        if (assignee == null && command != null && !command.internal()) {
            ticket.setAssignedAgentId(actor);
        }
        addParticipant(ticketId, actor, ticket.getShopId());
        StaffMessage clean = command == null ? new StaffMessage(null, false, List.of()) : command.withDefaults();
        TicketView result = support.reply(actor, ticketId, key,
                new Reply(clean.body(), clean.internal(), clean.attachmentMediaIds()), true, context);
        publishMessageChanged(ticketId, actor, clean.internal(), ticket.getCustomerId());
        return new SendResult(ticketId, summary(result, true, actor), false);
    }

    @Transactional
    public ConversationDetail claim(UUID actor, UUID ticketId) {
        requireStaff(actor);
        SupportTicketEntity ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội thoại."));
        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new com.utewedding.app.exception.BusinessConflictException("Hội thoại đã đóng.");
        }
        if (ticket.getAssignedAgentId() != null && !ticket.getAssignedAgentId().equals(actor)) {
            UserEntity assignedUser = users.findById(ticket.getAssignedAgentId()).orElse(null);
            String name = assignedUser == null || assignedUser.getDisplayName() == null
                    ? "nhân viên khác" : assignedUser.getDisplayName();
            throw new SupportChatParticipationRequiredException(name);
        }
        ticket.setAssignedAgentId(actor);
        addParticipant(ticketId, actor, ticket.getShopId());
        return detail(support.staffView(actor, ticketId), true, actor);
    }

    @Transactional
    public ConversationDetail join(UUID actor, UUID ticketId) {
        requireStaff(actor);
        SupportTicketEntity ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội thoại."));
        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new com.utewedding.app.exception.BusinessConflictException("Hội thoại đã đóng.");
        }
        addParticipant(ticketId, actor, ticket.getShopId());
        return detail(support.staffView(actor, ticketId), true, actor);
    }

    @Transactional
    public ConversationDetail staffChange(UUID actor, UUID ticketId, String key,
            SupportTicketStatus status, RequestContext context) {
        requireStaff(actor);
        TicketView updated = support.change(actor, ticketId, key,
                new SupportService.Change(status, null, null), context);
        events.publishEvent(new SupportChatEvent(ticketId, "CONVERSATION_CHANGED", actor, null,
                false, activeStaffIds()));
        return detail(updated, true, actor);
    }

    @Transactional
    public void markCustomerRead(UUID actor, UUID ticketId) {
        requireCustomer(actor);
        support.customerView(actor, ticketId);
        reads.markRead(ticketId, actor, Instant.now());
    }

    @Transactional
    public void markStaffRead(UUID actor, UUID ticketId) {
        requireStaff(actor);
        support.staffView(actor, ticketId);
        reads.markRead(ticketId, actor, Instant.now());
    }

    @Transactional(readOnly = true)
    public long customerUnreadCount(UUID actor) { requireCustomer(actor); return reads.countCustomerUnread(actor); }

    @Transactional(readOnly = true)
    public long staffUnreadCount(UUID actor) { requireStaff(actor); return reads.countStaffUnread(actor); }

    public List<UploadedImage> uploadImages(UUID actor, List<MultipartFile> files, boolean staff) {
        if (staff) requireStaff(actor); else requireCustomer(actor);
        if (files == null || files.isEmpty()) return List.of();
        if (files.size() > 5) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Mỗi tin nhắn đính kèm tối đa 5 ảnh.");
        List<UploadedImage> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            var metadata = mediaValidator.validate(file);
            if (!Set.of("image/jpeg", "image/png").contains(metadata.mimeType())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ chấp nhận ảnh JPEG hoặc PNG.");
            }
            ManagedMediaObjectEntity saved = media.upload(file, actor, "SUPPORT_ATTACHMENT", Duration.ofDays(365));
            uploaded.add(new UploadedImage(saved.getId(), metadata.mimeType(), file.getOriginalFilename()));
        }
        return List.copyOf(uploaded);
    }

    @Transactional(readOnly = true)
    public String customerAttachment(UUID actor, UUID ticketId, UUID mediaId) {
        requireCustomer(actor);
        return support.signedAttachment(actor, ticketId, mediaId, false);
    }

    @Transactional(readOnly = true)
    public String staffAttachment(UUID actor, UUID ticketId, UUID mediaId) {
        requireStaff(actor);
        return support.signedAttachment(actor, ticketId, mediaId, true);
    }

    @Transactional(readOnly = true)
    public void requireTypingAccess(UUID actor, UUID ticketId, boolean staff) {
        SupportTicketEntity ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội thoại."));
        if (staff) requireStaff(actor); else {
            requireCustomer(actor);
            if (!ticket.getCustomerId().equals(actor)
                    || ticket.getTicketKind() != com.utewedding.app.domain.SupportTicketKind.PLATFORM_SUPPORT) {
                throw new ResourceNotFoundException("Không tìm thấy hội thoại.");
            }
        }
    }

    @Transactional(readOnly = true)
    public UUID customerIdForTyping(UUID actor, UUID ticketId) {
        requireStaff(actor);
        return tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội thoại."))
                .getCustomerId();
    }

    @Transactional
    public void publishTyping(UUID ticketId, UUID actor, String name, boolean active, boolean staff,
                              boolean internal, UUID customerId) {
        requireTypingAccess(actor, ticketId, staff);
        if (!staff && !Objects.equals(customerId, actor)) {
            throw new AccessDeniedException("Hội thoại không thuộc tài khoản này.");
        }
        if (active) typing.mark(ticketId, actor, name); else typing.clear(ticketId, actor);
        if (staff && internal) return;
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.addAll(activeStaffIds());
        recipients.add(customerId);
        recipients.remove(actor);
        events.publishEvent(new SupportChatEvent(ticketId, "TYPING", actor, name, active, recipients));
    }

    @Transactional(readOnly = true)
    public List<TypingUser> activeTyping(UUID actor, UUID ticketId, boolean staff) {
        requireTypingAccess(actor, ticketId, staff);
        return typing.active(ticketId, actor);
    }

    private MessagePage messagePage(UUID ticketId, UUID actor, boolean staff, int page) {
        List<String> visibility = staff ? List.of("CUSTOMER", "INTERNAL") : List.of("CUSTOMER");
        Page<SupportMessageEntity> found = messages.findByTicketIdAndVisibilityInOrderByCreatedAtDescIdDesc(
                ticketId, visibility, PageRequest.of(clamp(page), 50));
        List<SupportMessageEntity> rows = new ArrayList<>(found.getContent());
        Collections.reverse(rows);
        List<UUID> ids = rows.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<UUID>> mediaByMessage = new HashMap<>();
        if (!ids.isEmpty()) attachments.findByMessageIdIn(ids).forEach(item -> mediaByMessage
                .computeIfAbsent(item.getId().getMessageId(), ignored -> new ArrayList<>())
                .add(item.getId().getManagedMediaId()));
        Set<UUID> authorIds = rows.stream().map(SupportMessageEntity::getAuthorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserEntity> authorMap = users.findAllById(authorIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, user -> user));
        String root = staff ? "/admin/support/chat/conversations/" : "/support/chat/conversations/";
        List<ChatMessage> output = rows.stream().map(message -> {
            UserEntity author = authorMap.get(message.getAuthorId());
            List<ChatAttachment> attached = mediaByMessage.getOrDefault(message.getId(), List.of()).stream()
                    .map(mediaId -> new ChatAttachment(mediaId, root + ticketId + "/attachments/" + mediaId)).toList();
            return new ChatMessage(message.getId(), message.getAuthorId(),
                    author == null || author.getDisplayName() == null ? "Tài khoản đã xóa" : author.getDisplayName(),
                    author == null ? "UNKNOWN" : author.getRole().name(), message.getVisibility(),
                    message.getBody(), attached, message.getCreatedAt(), actor.equals(message.getAuthorId()));
        }).toList();
        return new MessagePage(output, found.getNumber(), found.hasNext(), found.getTotalElements());
    }

    private ConversationSummary summary(TicketView ticket, boolean staff, UUID actor) {
        MessageView latest = ticket.messages().stream().filter(message -> staff || "CUSTOMER".equals(message.visibility()))
                .max(Comparator.comparing(MessageView::createdAt).thenComparing(MessageView::id)).orElse(null);
        String preview = latest == null ? "" : latest.body();
        if (latest != null && preview.isBlank() && !latest.attachments().isEmpty()) preview = "Đã gửi ảnh";
        if (preview.length() > 100) preview = preview.substring(0, 100) + "…";
        long unread = staff ? reads.countStaffUnreadForTicket(ticket.id(), ticket.customerId(), actor)
                : reads.countCustomerUnreadForTicket(ticket.id(), ticket.customerId());
        UUID assignee = ticket.assigneeId();
        UserEntity assigned = assignee == null ? null : users.findById(assignee).orElse(null);
        return new ConversationSummary(ticket.id(), ticket.number(), ticket.subject(), ticket.category().name(),
                ticket.status().name(), ticket.priority().name(), ticket.orderId(), ticket.returnCaseId(),
                ticket.fulfillmentId(), assignee, assigned == null ? null : assigned.getDisplayName(),
                ticket.lastActivityAt(), preview, unread, ticket.replyState().name());
    }

    private ConversationDetail detail(TicketView ticket, boolean staff, UUID actor) {
        List<Participant> listed = participants.findByIdTicketIdOrderByJoinedAtAsc(ticket.id()).stream()
                .map(SupportTicketParticipantEntity::getId).map(SupportTicketParticipantId::getUserId).distinct()
                .map(id -> users.findById(id).map(user -> new Participant(id, user.getDisplayName(), user.getRole().name())).orElse(null))
                .filter(Objects::nonNull).toList();
        UUID assignedId = ticket.assigneeId();
        UserEntity assigned = assignedId == null ? null : users.findById(assignedId).orElse(null);
        UserEntity customer = users.findById(ticket.customerId()).orElse(null);
        boolean canSend = staff
                ? ticket.status() != SupportTicketStatus.CLOSED
                : ticket.status() != SupportTicketStatus.CLOSED
                        && (ticket.status() != SupportTicketStatus.RESOLVED
                                || support.customerMayReopen(actor, ticket.id()));
        return new ConversationDetail(ticket.id(), ticket.number(), ticket.subject(), ticket.category().name(),
                ticket.status().name(), ticket.priority().name(), ticket.customerId(),
                customer == null ? "Khách hàng" : customer.getDisplayName(), ticket.orderId(), ticket.returnCaseId(), ticket.fulfillmentId(), assignedId,
                assigned == null ? null : assigned.getDisplayName(), listed,
                staff && participants.existsByIdTicketIdAndIdUserId(ticket.id(), actor),
                staff ? reads.countStaffUnreadForTicket(ticket.id(), ticket.customerId(), actor)
                        : reads.countCustomerUnreadForTicket(ticket.id(), ticket.customerId()),
                outsideBusinessHours(), canSend);
    }

    private void addParticipant(UUID ticketId, UUID userId, UUID shopId) {
        if (!participants.existsByIdTicketIdAndIdUserId(ticketId, userId)) {
            participants.saveAndFlush(new SupportTicketParticipantEntity(
                    new SupportTicketParticipantId(ticketId, userId), shopId));
        }
    }

    private UserEntity requireCustomer(UUID actor) {
        UserEntity user = users.findById(actor).orElseThrow(() -> new AccessDeniedException("Đăng nhập để chat với hỗ trợ."));
        if (user.getStatus() != UserStatus.ACTIVE || !Set.of(UserRole.USER, UserRole.CUSTOMER).contains(user.getRole()))
            throw new AccessDeniedException("Chỉ tài khoản khách đang hoạt động mới được chat.");
        return user;
    }

    private UserEntity requireStaff(UUID actor) {
        UserEntity user = users.findById(actor).orElseThrow(() -> new AccessDeniedException("Không có quyền hỗ trợ."));
        if (user.getStatus() != UserStatus.ACTIVE || !STAFF_ROLES.contains(user.getRole()))
            throw new AccessDeniedException("Không có quyền hỗ trợ.");
        return user;
    }

    private Set<UUID> activeStaffIds() {
        return users.findActiveSupportUsers(List.of(UserRole.MANAGER, UserRole.ADMIN, UserRole.SUPER_ADMIN)).stream()
                .map(UserEntity::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void publishMessageChanged(UUID ticketId, UUID actor, boolean internal, UUID customerId) {
        Set<UUID> recipients = activeStaffIds();
        if (!internal) recipients.add(customerId);
        recipients.add(actor);
        events.publishEvent(new SupportChatEvent(ticketId, "MESSAGE_CHANGED", actor, null, false, recipients));
    }

    private static String subjectFrom(String body, List<UUID> attachments) {
        String subject = body == null ? "" : body.trim().replaceAll("\\s+", " ");
        if (subject.isBlank()) subject = "Hình ảnh hỗ trợ";
        return subject.length() > 120 ? subject.substring(0, 120) : subject;
    }

    private static boolean outsideBusinessHours() {
        ZonedDateTime now = Instant.now().atZone(VIETNAM);
        return now.getDayOfWeek().getValue() > 5 || now.toLocalTime().isBefore(LocalTime.of(8, 0))
                || !now.toLocalTime().isBefore(LocalTime.of(17, 0));
    }

    private static int clamp(int page) { return Math.max(0, Math.min(page, 10000)); }

    public record CustomerMessage(String body, List<UUID> attachmentMediaIds, UUID orderId,
                                  UUID returnCaseId, UUID fulfillmentId) {
        CustomerMessage withDefaults() {
            return new CustomerMessage(body, attachmentMediaIds == null ? List.of() : List.copyOf(attachmentMediaIds),
                    orderId, returnCaseId, fulfillmentId);
        }
    }
    public record StaffMessage(String body, boolean internal, List<UUID> attachmentMediaIds) {
        StaffMessage withDefaults() { return new StaffMessage(body, internal,
                attachmentMediaIds == null ? List.of() : List.copyOf(attachmentMediaIds)); }
    }
    public record ConversationSummary(UUID id, String number, String subject, String category,
            String status, String priority, UUID orderId, UUID returnCaseId, UUID fulfillmentId,
            UUID assigneeId, String assigneeName, Instant lastActivityAt, String preview,
            long unreadMessages, String replyState) { }
    public record ConversationDetail(UUID id, String number, String subject, String category,
            String status, String priority, UUID customerId, String customerName, UUID orderId, UUID returnCaseId,
            UUID fulfillmentId, UUID assigneeId, String assigneeName, List<Participant> participants,
            boolean currentUserParticipates, long unreadMessages, boolean outsideBusinessHours, boolean canSend) {
        /** Compatibility constructor for integrations compiled against the pre-policy DTO. */
        public ConversationDetail(UUID id, String number, String subject, String category,
                String status, String priority, UUID customerId, String customerName, UUID orderId,
                UUID returnCaseId, UUID fulfillmentId, UUID assigneeId, String assigneeName,
                List<Participant> participants, boolean currentUserParticipates, long unreadMessages,
                boolean outsideBusinessHours) {
            this(id, number, subject, category, status, priority, customerId, customerName, orderId,
                    returnCaseId, fulfillmentId, assigneeId, assigneeName, participants,
                    currentUserParticipates, unreadMessages, outsideBusinessHours,
                    !"CLOSED".equals(status));
        }
    }
    public record Participant(UUID id, String name, String role) { }
    public record ChatMessage(UUID id, UUID authorId, String authorName, String authorRole,
            String visibility, String body, List<ChatAttachment> attachments, Instant createdAt,
            boolean authoredByCurrentUser) { }
    public record ChatAttachment(UUID id, String url) { }
    public record MessagePage(List<ChatMessage> messages, int page, boolean hasMore, long total) { }
    public record SendResult(UUID conversationId, ConversationSummary conversation, boolean outsideBusinessHours) { }
    public record UploadedImage(UUID id, String mimeType, String name) { }
    public record TypingUser(UUID id, String name) { }

    public record SupportChatEvent(UUID ticketId, String type, UUID actorId, String actorName,
                                   boolean active, Set<UUID> recipients) { }
}
