package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.*;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SupportService {
    public static final String NOTIFICATION_EVENT = "SUPPORT_NOTIFICATION";
    private final SupportTicketRepository tickets; private final SupportMessageRepository messages;
    private final SupportMessageAttachmentRepository attachments; private final SupportIdempotencyRepository idempotencies;
    private final ManagedMediaObjectRepository media; private final OrderRepository orders;
    private final ReturnCaseRepository returnCases; private final ReturnDisputeRepository disputes;
    private final OrderFulfillmentRepository fulfillments; private final UserRepository users;
    private final ManagedMediaService managedMedia; private final OutboxService outbox; private final AuditLogPort audit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WeddingRepository weddings;

    public SupportService(SupportTicketRepository t, SupportMessageRepository m,
                          SupportMessageAttachmentRepository a, SupportIdempotencyRepository i,
                          ManagedMediaObjectRepository media, OrderRepository orders,
                          ReturnCaseRepository returnCases, ReturnDisputeRepository disputes,
                          OrderFulfillmentRepository fulfillments, UserRepository users,
                          ManagedMediaService managedMedia, OutboxService outbox, AuditLogPort audit) {
        tickets=t;messages=m;attachments=a;idempotencies=i;this.media=media;this.orders=orders;
        this.returnCases=returnCases;this.disputes=disputes;this.fulfillments=fulfillments;
        this.users=users;this.managedMedia=managedMedia;this.outbox=outbox;this.audit=audit;
    }

    @Transactional
    public TicketView create(UUID customerId, String key, CreateTicket command, RequestContext context) {
        var customer=users.findById(customerId).orElseThrow(()->new AccessDeniedException("Không có quyền tạo ticket."));
        if(customer.getStatus()!=UserStatus.ACTIVE || !(customer.getRole()==UserRole.USER||customer.getRole()==UserRole.CUSTOMER))throw new AccessDeniedException("Chỉ khách được tạo ticket của mình.");
        if(command==null||command.category()==null)throw new BusinessConflictException("Cần chọn loại hỗ trợ.");
        validateCustomerReferences(customerId, command);
        String requestHash = hash("CREATE|" + command);
        SupportIdempotencyEntity prior = prior(customerId, key, requestHash);
        if (prior != null) return customerView(customerId, prior.getTicketId());
        SupportTicketEntity ticket = new SupportTicketEntity(); ticket.setCustomerId(customerId);
        ticket.setCategory(Objects.requireNonNull(command.category()));
        ticket.setSubject(required(command.subject(), 200)); ticket.setOrderId(command.orderId());
        ticket.setReturnCaseId(command.returnCaseId()); ticket.setReturnDisputeId(command.returnDisputeId());
        ticket.setFulfillmentId(command.fulfillmentId()); ticket = tickets.saveAndFlush(ticket);
        SupportMessageEntity message = new SupportMessageEntity(); message.setTicketId(ticket.getId());
        message.setShopId(ticket.getShopId());
        message.setAuthorId(customerId); message.setBody(bodyOrAttachment(command.body(), command.attachmentMediaIds()));
        message = messages.saveAndFlush(message); attach(customerId, message.getId(), ticket.getShopId(), command.attachmentMediaIds());
        saveIdempotency(customerId, key, requestHash, ticket.getId(), message.getId());
        enqueueForRecipients(ticket, supportStaffIdsExcept(customerId), "Có ticket hỗ trợ mới", context);
        audit.record("SUPPORT_TICKET_CREATED", customerId, "SupportTicket", ticket.getId(),
                Map.of("category", ticket.getCategory().name()), context);
        enqueueAttentionChange(ticket, context);
        return view(ticket, false);
    }

    @Transactional
    public TicketView createByStaff(UUID staffId, UUID customerId, UUID weddingId, String key,
                                    String subject, String body, List<UUID> attachmentMediaIds,
                                    RequestContext context) {
        requireStaff(staffId);
        UserEntity customer = users.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chủ thiệp."));
        if (customer.getStatus() != UserStatus.ACTIVE
                || (customer.getRole() != UserRole.USER && customer.getRole() != UserRole.CUSTOMER)) {
            throw new BusinessConflictException("Chỉ có thể gửi góp ý cho tài khoản khách đang hoạt động.");
        }
        if (weddings == null) throw new BusinessConflictException("Không thể xác minh thiệp cưới.");
        WeddingEntity wedding = weddings.findById(weddingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thiệp cưới."));
        if (!customerId.equals(wedding.getOwnerId())) {
            throw new BusinessConflictException("Người nhận không phải chủ sở hữu thiệp.");
        }
        String cleanSubject = required(subject, 200);
        String cleanBody = required(body, 5000);
        String requestHash = hash("ADMIN_WEDDING_FEEDBACK|" + customerId + "|" + weddingId
                + "|" + cleanSubject + "|" + cleanBody + "|" + attachmentMediaIds);
        SupportIdempotencyEntity prior = prior(staffId, key, requestHash);
        if (prior != null) return staffView(staffId, prior.getTicketId());

        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setCustomerId(customerId);
        ticket.setCreatedById(staffId);
        ticket.setWeddingId(weddingId);
        ticket.setCategory(SupportCategory.GENERAL);
        ticket.setStatus(SupportTicketStatus.WAITING_CUSTOMER);
        ticket.setSubject(cleanSubject);
        ticket = tickets.saveAndFlush(ticket);

        SupportMessageEntity message = new SupportMessageEntity();
        message.setTicketId(ticket.getId());
        message.setShopId(ticket.getShopId());
        message.setAuthorId(staffId);
        message.setVisibility("CUSTOMER");
        message.setBody(cleanBody);
        message = messages.saveAndFlush(message);
        attach(staffId, message.getId(), ticket.getShopId(), attachmentMediaIds);
        saveIdempotency(staffId, key, requestHash, ticket.getId(), message.getId());
        enqueueForRecipients(ticket, recipientExcept(customerId, staffId), "UTEWedding gửi góp ý về thiệp cưới", context);
        audit.record("WEDDING_FEEDBACK_TICKET_CREATED", staffId, "SupportTicket", ticket.getId(),
                Map.of("weddingId", weddingId, "customerId", customerId), context);
        enqueueAttentionChange(ticket, context);
        return view(ticket, true);
    }

    @Transactional
    public TicketView reply(UUID actorId, UUID ticketId, String key, Reply command,
                            boolean staff, RequestContext context) {
        SupportTicketEntity ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ticket."));
        if (staff) requireStaffTicketAccess(requireStaff(actorId), ticket); else {
            requireOwner(ticket, actorId);
            requirePlatformSupportForCustomer(ticket);
        }
        if (staff && command.internal() && ticket.getTicketKind() == SupportTicketKind.SHOP) {
            throw new BusinessConflictException("Không thể gửi ghi chú nội bộ trong hội thoại cửa hàng.");
        }
        String visibility = staff && command.internal() ? "INTERNAL" : "CUSTOMER";
        String requestHash = hash("REPLY|" + ticketId + "|" + visibility + "|" + command.body()
                + "|" + command.attachmentMediaIds());
        SupportIdempotencyEntity prior = prior(actorId, key, requestHash);
        if (prior != null) return view(ticket, staff);
        if (!staff && ticket.getStatus() == SupportTicketStatus.RESOLVED) {
            boolean inWindow = ticket.getResolvedAt() != null
                    && !ticket.getResolvedAt().isBefore(Instant.now().minus(Duration.ofDays(7)));
            if (!ticket.getStatus().canTransitionTo(SupportTicketStatus.OPEN, inWindow)) {
                throw new BusinessConflictException("Ticket đã hết thời hạn mở lại.");
            }
            ticket.setStatus(SupportTicketStatus.OPEN); ticket.setResolvedAt(null);
        } else if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new BusinessConflictException("Ticket đã đóng.");
        }
        SupportMessageEntity message = new SupportMessageEntity(); message.setTicketId(ticketId);
        message.setShopId(ticket.getShopId());
        message.setAuthorId(actorId); message.setVisibility(visibility);
        message.setBody(bodyOrAttachment(command.body(), command.attachmentMediaIds())); message = messages.saveAndFlush(message);
        attach(actorId, message.getId(), ticket.getShopId(), command.attachmentMediaIds()); ticket.touch();
        if (staff && !command.internal() && ticket.getStatus() != SupportTicketStatus.RESOLVED) {
            ticket.setStatus(SupportTicketStatus.WAITING_CUSTOMER);
        } else if (!staff && ticket.getStatus() == SupportTicketStatus.WAITING_CUSTOMER) {
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        }
        saveIdempotency(actorId, key, requestHash, ticketId, message.getId());
        Set<UUID> recipients = resolveReplyRecipients(ticket, actorId, staff, command.internal());
        String notification = staff
                ? (command.internal() ? "Có ghi chú nội bộ mới" : "Nhân viên đã phản hồi ticket")
                : "Khách hàng đã phản hồi ticket";
        enqueueForRecipients(ticket, recipients, notification, context);
        audit.record("SUPPORT_TICKET_REPLIED", actorId, "SupportTicket", ticketId,
                Map.of("visibility", visibility), context);
        if (!command.internal()) enqueueAttentionChange(ticket, context);
        return view(ticket, staff);
    }

    @Transactional
    public TicketView change(UUID actorId, UUID ticketId, String key, Change command,
                             RequestContext context) {
        UserEntity staff = requireStaff(actorId);
        SupportTicketEntity ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ticket."));
        requireStaffTicketAccess(staff, ticket);
        String requestHash = hash("CHANGE|" + ticketId + "|" + command);
        SupportIdempotencyEntity prior = prior(actorId, key, requestHash);
        if (prior != null) return view(ticket, true);
        if (command.status() != null && command.status() != ticket.getStatus()) {
            if (!ticket.getStatus().canTransitionTo(command.status(), false)) {
                throw new BusinessConflictException("Chuyển trạng thái ticket không hợp lệ.");
            }
            ticket.setStatus(command.status());
            if (command.status() == SupportTicketStatus.RESOLVED) ticket.setResolvedAt(Instant.now());
            if (command.status() == SupportTicketStatus.CLOSED) ticket.setClosedAt(Instant.now());
        }
        if (command.priority() != null) ticket.setPriority(command.priority());
        if (command.assigneeId() != null) {
            UserEntity assignee = requireStaff(command.assigneeId());
            if (ticket.getTicketKind() == SupportTicketKind.SHOP && assignee.getRole() == UserRole.MANAGER) {
                throw new BusinessConflictException("Hội thoại cửa hàng chỉ có thể giao cho quản trị viên.");
            }
            ticket.setAssignedAgentId(command.assigneeId());
        }
        ticket.touch(); saveIdempotency(actorId, key, requestHash, ticketId, null);
        enqueueForRecipients(ticket, recipientExcept(ticket.getCustomerId(), actorId),
                "Ticket hỗ trợ đã được cập nhật", context);
        audit.record("SUPPORT_TICKET_CHANGED", actorId, "SupportTicket", ticketId,
                Map.of("status", ticket.getStatus().name(), "priority", ticket.getPriority().name()), context);
        enqueueAttentionChange(ticket, context);
        return view(ticket, true);
    }

    @Transactional(readOnly=true) public boolean customerMayReopen(UUID actor,UUID id){var ticket=tickets.findById(id).orElseThrow();requireOwner(ticket,actor);requirePlatformSupportForCustomer(ticket);return ticket.getStatus()==SupportTicketStatus.RESOLVED && ticket.getResolvedAt()!=null && ticket.getResolvedAt().plus(Duration.ofDays(7)).isAfter(Instant.now());}
    @Transactional(readOnly=true) public List<TicketView> customerList(UUID customerId) {
        return views(tickets.findByCustomerIdOrderByLastActivityAtDesc(customerId).stream()
                .filter(ticket -> ticket.getTicketKind() == com.utewedding.app.domain.SupportTicketKind.PLATFORM_SUPPORT)
                .toList(), false);
    }
    @Transactional(readOnly=true) public TicketView customerView(UUID customerId,UUID id) {
        SupportTicketEntity t=tickets.findById(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy ticket."));requireOwner(t,customerId);requirePlatformSupportForCustomer(t);return view(t,false);
    }
    @Transactional(readOnly=true) public TicketView staffView(UUID actorId,UUID id){UserEntity staff=requireStaff(actorId);SupportTicketEntity ticket=tickets.findById(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy ticket."));requireStaffTicketAccess(staff,ticket);return view(ticket,true);}
    @Transactional(readOnly=true) public List<TicketView> staffQueue(UUID actorId){UserEntity staff=requireStaff(actorId);List<SupportTicketEntity> queue=staff.getRole()==UserRole.MANAGER?tickets.findFirst100ByTicketKindAndStatusNotOrderByPriorityDescLastActivityAtAsc(SupportTicketKind.PLATFORM_SUPPORT,SupportTicketStatus.CLOSED):tickets.findFirst100ByStatusNotOrderByPriorityDescLastActivityAtAsc(SupportTicketStatus.CLOSED);return views(queue,true);}

    public ManagedMediaObjectEntity uploadAttachment(UUID actorId, UUID ticketId, MultipartFile file, boolean staff) {
        SupportTicketEntity t=tickets.findById(ticketId).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy ticket."));if(staff)requireStaffTicketAccess(requireStaff(actorId),t);else {requireOwner(t,actorId);requirePlatformSupportForCustomer(t);if(t.getStatus()==SupportTicketStatus.CLOSED || t.getStatus()==SupportTicketStatus.RESOLVED && !customerMayReopen(actorId,ticketId))throw new BusinessConflictException("Ticket không nhận phản hồi.");}return managedMedia.upload(file,actorId,"SUPPORT_ATTACHMENT",Duration.ofDays(365));
    }
    @Transactional(readOnly=true) public String signedAttachment(UUID actorId,UUID ticketId,UUID mediaId,boolean staff){SupportTicketEntity t=tickets.findById(ticketId).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy ticket."));if(staff)requireStaffTicketAccess(requireStaff(actorId),t);else {requireOwner(t,actorId);requirePlatformSupportForCustomer(t);}List<SupportMessageEntity> visible=staff?messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId):messages.findByTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(ticketId,"CUSTOMER");List<UUID> messageIds=visible.stream().map(SupportMessageEntity::getId).toList();boolean linked=!messageIds.isEmpty()&&attachments.findByMessageIdIn(messageIds).stream().anyMatch(a->mediaId.equals(a.getId().getManagedMediaId()));if(!linked)throw new AccessDeniedException("Media không thuộc ticket.");return managedMedia.signedUrl(mediaId);}

    @Scheduled(cron="${support.auto-close-cron:0 15 * * * *}") @SchedulerLock(name="support-auto-close",lockAtMostFor="10m")
    @Transactional public void autoClose(){tickets.autoClose(Instant.now().minus(Duration.ofDays(7)),Instant.now());}

    @Transactional(readOnly=true)
    public org.springframework.data.domain.Page<TicketView> search(UUID actor,boolean staff,SupportTicketStatus status,SupportPriority priority,UUID assignee,SupportCategory category,String q,int page) {
        return searchInternal(actor,staff,status,priority,assignee,category,q,false,false,page);
    }
    @Transactional(readOnly=true) public org.springframework.data.domain.Page<TicketView> staffSearch(UUID actor,SupportTicketStatus status,SupportPriority priority,UUID assignee,SupportCategory category,String q,boolean unassigned,int page){return staffSearch(actor,status,priority,assignee,category,q,unassigned,false,page);}
    @Transactional(readOnly=true) public org.springframework.data.domain.Page<TicketView> staffSearch(UUID actor,SupportTicketStatus status,SupportPriority priority,UUID assignee,SupportCategory category,String q,boolean unassigned,boolean needsReply,int page){return searchInternal(actor,true,status,priority,assignee,category,q,unassigned,needsReply,page);}
    public long countNeedsStaffReply() { return tickets.countNeedsStaffReply(); }
    private org.springframework.data.domain.Page<TicketView> searchInternal(UUID actor,boolean staff,SupportTicketStatus status,SupportPriority priority,UUID assignee,SupportCategory category,String q,boolean unassigned,boolean needsReply,int page){
        UserEntity staffActor = staff ? requireStaff(actor) : null;
        var result = tickets.findAll((root,query,builder)-> {
            var filters=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(!staff) {
                filters.add(builder.equal(root.get("customerId"),actor));
                filters.add(builder.equal(root.get("ticketKind"), com.utewedding.app.domain.SupportTicketKind.PLATFORM_SUPPORT));
            } else if (staffActor.getRole() == UserRole.MANAGER) {
                filters.add(builder.equal(root.get("ticketKind"), SupportTicketKind.PLATFORM_SUPPORT));
            }
            if(status!=null)filters.add(builder.equal(root.get("status"),status));
            if(priority!=null)filters.add(builder.equal(root.get("priority"),priority));
            if(unassigned)filters.add(builder.isNull(root.get("assignedAgentId")));
            if(assignee!=null)filters.add(builder.equal(root.get("assignedAgentId"),assignee));
            if(category!=null)filters.add(builder.equal(root.get("category"),category));
            if(q!=null&&!q.isBlank())filters.add(builder.or(builder.like(builder.lower(root.get("subject")),"%"+q.trim().toLowerCase(java.util.Locale.ROOT)+"%"),builder.like(root.get("ticketNumber"),"%"+q.trim()+"%")));
            if(needsReply) {
                query.distinct(true);
                var latestCustomerMessage = query.subquery(UUID.class);
                var message = latestCustomerMessage.from(SupportMessageEntity.class);
                var newerPublicMessage = latestCustomerMessage.subquery(UUID.class);
                var newer = newerPublicMessage.from(SupportMessageEntity.class);
                newerPublicMessage.select(newer.get("id")).where(
                        builder.equal(newer.get("ticketId"), message.get("ticketId")),
                        builder.equal(newer.get("visibility"), "CUSTOMER"),
                        builder.or(builder.greaterThan(newer.<Instant>get("createdAt"), message.<Instant>get("createdAt")),
                                builder.and(builder.equal(newer.get("createdAt"), message.get("createdAt")),
                                        builder.greaterThan(newer.<UUID>get("id"), message.<UUID>get("id")))));
                latestCustomerMessage.select(message.get("id")).where(
                        builder.equal(message.get("ticketId"), root.get("id")),
                        builder.equal(message.get("visibility"), "CUSTOMER"),
                        builder.equal(message.get("authorId"), root.get("customerId")),
                        builder.not(builder.exists(newerPublicMessage)));
                filters.add(builder.not(root.get("status").in(SupportTicketStatus.RESOLVED, SupportTicketStatus.CLOSED)));
                filters.add(builder.exists(latestCustomerMessage));
            }
            return builder.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
        },org.springframework.data.domain.PageRequest.of(Math.max(0,page),20,org.springframework.data.domain.Sort.by("lastActivityAt").descending()));
        return new org.springframework.data.domain.PageImpl<>(views(result.getContent(), staff), result.getPageable(), result.getTotalElements());
    }
    private TicketView view(SupportTicketEntity ticket,boolean staff){return views(List.of(ticket),staff).getFirst();}
    private List<TicketView> views(Collection<SupportTicketEntity> ticketRows, boolean staff) {
        if (ticketRows.isEmpty()) return List.of();
        List<UUID> ticketIds = ticketRows.stream().map(SupportTicketEntity::getId).filter(Objects::nonNull).toList();
        List<SupportMessageEntity> allMessages = ticketIds.isEmpty() ? List.of()
                : messages.findByTicketIdInOrderByCreatedAtAscIdAsc(ticketIds);
        if (!staff) allMessages = allMessages.stream().filter(m -> "CUSTOMER".equals(m.getVisibility())).toList();
        Map<UUID, List<SupportMessageEntity>> messagesByTicket = allMessages.stream()
                .collect(Collectors.groupingBy(SupportMessageEntity::getTicketId, LinkedHashMap::new, Collectors.toList()));
        List<UUID> messageIds = allMessages.stream().map(SupportMessageEntity::getId).toList();
        Map<UUID, List<UUID>> mediaByMessage = new HashMap<>();
        if (!messageIds.isEmpty()) {
            attachments.findByMessageIdIn(messageIds).forEach(attachment -> mediaByMessage
                    .computeIfAbsent(attachment.getId().getMessageId(), ignored -> new ArrayList<>())
                    .add(attachment.getId().getManagedMediaId()));
        }
        return ticketRows.stream().map(ticket -> new TicketView(ticket.getId(), ticket.getTicketNumber(),
                ticket.getCustomerId(), ticket.getCategory(), ticket.getPriority(), ticket.getStatus(), ticket.getSubject(),
                ticket.getOrderId(), ticket.getReturnCaseId(), ticket.getReturnDisputeId(), ticket.getFulfillmentId(),
                ticket.getWeddingId(), ticket.getCreatedById(), ticket.getAssignedAgentId(),
                messagesByTicket.getOrDefault(ticket.getId(), List.of()).stream()
                .map(message -> new MessageView(message.getId(), message.getAuthorId(), message.getVisibility(), message.getBody(),
                        mediaByMessage.getOrDefault(message.getId(), List.of()), message.getCreatedAt())).toList(),
                ticket.getCreatedAt(), ticket.getLastActivityAt(), replyState(ticket,
                        messagesByTicket.getOrDefault(ticket.getId(), List.of())))).toList();
    }
    private ReplyState replyState(SupportTicketEntity ticket, List<SupportMessageEntity> messages) {
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED || ticket.getStatus() == SupportTicketStatus.CLOSED) {
            return ReplyState.NONE;
        }
        SupportMessageEntity latestPublic = messages.stream()
                .filter(message -> "CUSTOMER".equals(message.getVisibility()))
                .max(Comparator.comparing(SupportMessageEntity::getCreatedAt)
                        .thenComparing(SupportMessageEntity::getId)).orElse(null);
        if (latestPublic == null) return ReplyState.NEEDS_STAFF;
        if (ticket.getCustomerId().equals(latestPublic.getAuthorId())) {
            boolean staffHasReplied = messages.stream().anyMatch(message -> "CUSTOMER".equals(message.getVisibility())
                    && !ticket.getCustomerId().equals(message.getAuthorId()));
            return staffHasReplied ? ReplyState.CUSTOMER_REPLIED : ReplyState.NEEDS_STAFF;
        }
        return ReplyState.WAITING_CUSTOMER;
    }

    private void enqueueAttentionChange(SupportTicketEntity ticket, RequestContext context) {
        String activity = ticket.getLastActivityAt() == null ? Long.toString(System.nanoTime())
                : Long.toString(ticket.getLastActivityAt().toEpochMilli());
        outbox.enqueue("ADMIN_ATTENTION_CHANGED", "SupportTicket", ticket.getId(),
                "admin-attention:support:" + ticket.getId() + ":" + activity, "{}", context);
    }
    private void attach(UUID actor,UUID messageId,UUID shopId,List<UUID> ids){if(ids==null)return;for(UUID id:new LinkedHashSet<>(ids)){ManagedMediaObjectEntity m=media.findById(id).orElseThrow(()->new BusinessConflictException("Không tìm thấy attachment."));if(!actor.equals(m.getOwnerId())||m.getStatus()!=ManagedMediaStatus.READY||!"SUPPORT_ATTACHMENT".equals(m.getPurpose())||!"authenticated".equals(m.getDeliveryType()))throw new AccessDeniedException("Attachment không thuộc actor hoặc không hợp lệ.");attachments.save(new SupportMessageAttachmentEntity(new SupportMessageAttachmentId(messageId,id),shopId));}}
    public void validateCustomerReferences(UUID customer,CreateTicket c){if(c.orderId()!=null&&orders.findByIdAndUserId(c.orderId(),customer).isEmpty())throw new AccessDeniedException("Order không thuộc customer.");ReturnCaseEntity r=null;if(c.returnCaseId()!=null)r=returnCases.findByIdAndCustomerId(c.returnCaseId(),customer).orElseThrow(()->new AccessDeniedException("Return case không thuộc customer."));if(c.returnDisputeId()!=null){ReturnDisputeEntity d=disputes.findById(c.returnDisputeId()).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy dispute."));if(r==null||!d.getReturnCaseId().equals(r.getId()))throw new AccessDeniedException("Dispute không thuộc return case.");}if(c.fulfillmentId()!=null){OrderFulfillmentEntity f=fulfillments.findById(c.fulfillmentId()).orElseThrow();if(orders.findByIdAndUserId(f.getOrderId(),customer).isEmpty())throw new AccessDeniedException("Fulfillment không thuộc customer.");}}
    private void requireOwner(SupportTicketEntity t,UUID actor){if(!t.getCustomerId().equals(actor))throw new AccessDeniedException("Ticket không thuộc customer.");}
    private void requirePlatformSupportForCustomer(SupportTicketEntity ticket) {
        if (ticket.getTicketKind() != com.utewedding.app.domain.SupportTicketKind.PLATFORM_SUPPORT) {
            throw new ResourceNotFoundException("Không tìm thấy ticket.");
        }
    }
    private UserEntity requireStaff(UUID actor){UserEntity u=users.findById(actor).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy tài khoản."));if(u.getStatus()!=UserStatus.ACTIVE || (u.getRole()!=UserRole.MANAGER&&u.getRole()!=UserRole.ADMIN&&u.getRole()!=UserRole.SUPER_ADMIN))throw new AccessDeniedException("Không có quyền support.");return u;}
    private void requireStaffTicketAccess(UserEntity staff, SupportTicketEntity ticket){if(staff.getRole()==UserRole.MANAGER&&ticket.getTicketKind()==SupportTicketKind.SHOP)throw new AccessDeniedException("Chỉ quản trị viên mới có quyền xem hội thoại cửa hàng.");}
    private SupportIdempotencyEntity prior(UUID actor,String key,String hash){if(key==null||key.isBlank()||key.length()>190)throw new BusinessConflictException("Idempotency-Key là bắt buộc.");SupportIdempotencyEntity p=idempotencies.findById(new SupportIdempotencyId(actor,key.trim())).orElse(null);if(p!=null&&!p.getRequestHash().equals(hash))throw new BusinessConflictException("Idempotency-Key đã dùng với payload khác.");return p;}
    private void saveIdempotency(UUID actor,String key,String hash,UUID ticket,UUID message){try{idempotencies.saveAndFlush(new SupportIdempotencyEntity(new SupportIdempotencyId(actor,key.trim()),hash,ticket,message));}catch(DataIntegrityViolationException e){throw new BusinessConflictException("Yêu cầu đang được xử lý.");}}
    private Set<UUID> resolveReplyRecipients(SupportTicketEntity ticket, UUID actorId, boolean staff, boolean internal) {
        if (staff && !internal) return recipientExcept(ticket.getCustomerId(), actorId);
        if (ticket.getAssignedAgentId() != null) return recipientExcept(ticket.getAssignedAgentId(), actorId);
        return supportStaffIdsExcept(actorId);
    }
    private Set<UUID> supportStaffIdsExcept(UUID excluded) {
        return users.findActiveSupportUsers(List.of(UserRole.MANAGER, UserRole.ADMIN, UserRole.SUPER_ADMIN)).stream()
                .map(UserEntity::getId).filter(Objects::nonNull).filter(id -> !id.equals(excluded))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private Set<UUID> recipientExcept(UUID recipient, UUID excluded) {
        return recipient == null || recipient.equals(excluded) ? Set.of() : Set.of(recipient);
    }
    private void enqueueForRecipients(SupportTicketEntity ticket, Collection<UUID> recipients, String text, RequestContext context) {
        if (recipients == null || recipients.isEmpty()) return;
        long activity = ticket.getLastActivityAt() == null ? System.nanoTime() : ticket.getLastActivityAt().toEpochMilli();
        for (UUID recipient : new LinkedHashSet<>(recipients)) {
            if (recipient == null || recipient.equals(ticket.getCustomerId()) && "Có ghi chú nội bộ mới".equals(text)) continue;
            String key = "support:" + ticket.getId() + ":" + activity + ":" + recipient;
            outbox.enqueue(NOTIFICATION_EVENT, "SupportTicket", ticket.getId(), key,
                    recipient + "|" + ticket.getId() + "|" + text, context);
        }
    }
    private static String required(String s,int max){if(s==null||s.isBlank())throw new BusinessConflictException("Nội dung là bắt buộc.");String v=s.trim();if(v.length()>max)throw new BusinessConflictException("Nội dung quá dài.");return v;}private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private static String bodyOrAttachment(String value, List<UUID> attachmentIds) {
        if (value == null || value.isBlank()) {
            if (attachmentIds == null || attachmentIds.isEmpty()) {
                throw new BusinessConflictException("Hãy nhập tin nhắn hoặc đính kèm ảnh.");
            }
            return "";
        }
        String clean = value.trim();
        if (clean.length() > 5000) throw new BusinessConflictException("Nội dung quá dài.");
        return clean;
    }

    public record CreateTicket(SupportCategory category,String subject,String body,UUID orderId,UUID returnCaseId,UUID returnDisputeId,UUID fulfillmentId,List<UUID> attachmentMediaIds){}
    public record Reply(String body,boolean internal,List<UUID> attachmentMediaIds){}
    public record Change(SupportTicketStatus status,SupportPriority priority,UUID assigneeId){}
    public record MessageView(UUID id,UUID authorId,String visibility,String body,List<UUID> attachments,Instant createdAt){}
    public enum ReplyState { NEEDS_STAFF, CUSTOMER_REPLIED, WAITING_CUSTOMER, NONE }
    public record TicketView(UUID id,String number,UUID customerId,SupportCategory category,SupportPriority priority,SupportTicketStatus status,String subject,UUID orderId,UUID returnCaseId,UUID returnDisputeId,UUID fulfillmentId,UUID weddingId,UUID createdById,UUID assigneeId,List<MessageView> messages,Instant createdAt,Instant lastActivityAt,ReplyState replyState){
        public TicketView(UUID id,String number,UUID customerId,SupportCategory category,SupportPriority priority,SupportTicketStatus status,String subject,UUID orderId,UUID returnCaseId,UUID returnDisputeId,UUID fulfillmentId,UUID assigneeId,List<MessageView> messages,Instant createdAt,Instant lastActivityAt){
            this(id,number,customerId,category,priority,status,subject,orderId,returnCaseId,returnDisputeId,fulfillmentId,null,null,assigneeId,messages,createdAt,lastActivityAt,ReplyState.NONE);
        }
    }
}
