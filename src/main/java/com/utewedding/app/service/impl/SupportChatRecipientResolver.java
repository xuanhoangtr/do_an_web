package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.SupportTicketEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.repository.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SupportChatRecipientResolver {
    private final SupportTicketRepository tickets;
    private final ShopSupportOwnerRepository owners;
    private final ShopRepository shops;
    private final UserRepository users;
    public SupportChatRecipientResolver(SupportTicketRepository tickets, ShopSupportOwnerRepository owners,
            ShopRepository shops, UserRepository users) {
        this.tickets = tickets; this.owners = owners; this.shops = shops; this.users = users;
    }
    public SupportTicketEntity ticket(UUID id) { return id == null ? null : tickets.findById(id).orElse(null); }
    public boolean allowed(SupportTicketEntity ticket, UUID recipient) {
        if (ticket == null || recipient == null) return false;
        SupportTicketKind ticketKind = ticket.getTicketKind();
        if (ticketKind == null) return false;
        if (ticketKind != SupportTicketKind.SHOP) {
            if (ticketKind != SupportTicketKind.PLATFORM_SUPPORT) return false;
            UserEntity platformUser = users.findById(recipient).orElse(null);
            if (platformUser == null || platformUser.getStatus() != UserStatus.ACTIVE) return false;
            UserRole platformRole = platformUser.getRole();
            if (recipient.equals(ticket.getCustomerId())) {
                return platformRole == UserRole.USER || platformRole == UserRole.CUSTOMER;
            }
            return platformRole == UserRole.MANAGER || platformRole == UserRole.ADMIN
                    || platformRole == UserRole.SUPER_ADMIN;
        }
        boolean activeShop = ticket.getShopId() != null && shops.findById(ticket.getShopId()).map(shop ->
                shop.getKind() == ShopKind.VENDOR && shop.getStatus() == ShopStatus.ACTIVE).orElse(false);
        if (!activeShop) return false;
        UserEntity user = users.findById(recipient).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) return false;

        UserRole role = user.getRole();
        if (recipient.equals(ticket.getCustomerId())) {
            return role == UserRole.USER || role == UserRole.CUSTOMER;
        }
        if (role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN) return true;

        // Membership is the tenant boundary. Allow legacy customer-role owners as
        // well as accounts promoted to VENDOR, but never unrelated roles.
        boolean ownerRole = role == UserRole.VENDOR || role == UserRole.USER || role == UserRole.CUSTOMER;
        return ownerRole && owners.findActiveOwnerIds(ticket.getShopId()).contains(recipient);
    }
    public java.util.Set<UUID> recipients(SupportTicketEntity ticket) {
        if (ticket == null || ticket.getTicketKind() != SupportTicketKind.SHOP || ticket.getShopId() == null) {
            return Set.of();
        }
        List<UUID> ownerIds = owners.findActiveOwnerIds(ticket.getShopId());
        java.util.Set<UUID> result = new LinkedHashSet<>(ownerIds == null ? List.of() : ownerIds);
        if (ticket.getCustomerId() != null) result.add(ticket.getCustomerId());
        List<UserEntity> administrators = users.findActiveSupportUsers(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN));
        if (administrators != null) {
            administrators.stream().map(UserEntity::getId).filter(id -> id != null).forEach(result::add);
        }
        result.removeIf(id -> !allowed(ticket, id));
        return result;
    }
    public String target(SupportTicketEntity ticket, UUID recipient) {
        if (ticket.getTicketKind() != SupportTicketKind.SHOP) return null;
        if (recipient.equals(ticket.getCustomerId())) {
            return "/support/shops/" + ticket.getShopId() + "?conversationId=" + ticket.getId();
        }
        if (owners.findActiveOwnerIds(ticket.getShopId()).contains(recipient)) {
            return "/vendor/chat?conversationId=" + ticket.getId();
        }
        if (allowed(ticket, recipient)) return "/admin/support?conversationId=" + ticket.getId();
        return null;
    }
}
