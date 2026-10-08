package com.storagehub.service;

import com.storagehub.api.support.CreateSupportMessageRequest;
import com.storagehub.api.support.CreateSupportTicketRequest;
import com.storagehub.api.support.SupportTicketResponse;
import com.storagehub.api.support.UpdateSupportTicketRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SupportTicket;
import com.storagehub.domain.model.SupportTicketMessage;
import com.storagehub.domain.model.SupportTicketStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.SupportTicketMessageRepository;
import com.storagehub.domain.repo.SupportTicketRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FacilityRepository facilityRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<SupportTicketResponse> listCustomer(
        ActorPrincipal actor,
        SupportTicketStatus status,
        int page,
        int size,
        String correlationId
    ) {
        requireCustomer(actor);
        PageRequest pageable = pageRequest(page, size);
        var result = status == null
            ? ticketRepository.findByCustomer_Id(actor.userId(), pageable)
            : ticketRepository.findByCustomer_IdAndStatus(actor.userId(), status, pageable);
        return PageResponse.from(result.map(this::toResponse), correlationId);
    }

    @Transactional
    public SupportTicketResponse createCustomer(
        ActorPrincipal actor,
        CreateSupportTicketRequest request
    ) {
        requireCustomer(actor);
        Facility facility = resolveFacilityForCustomer(actor, request.facilityId());
        User customer = userRepository.getReferenceById(actor.userId());

        SupportTicket ticket = new SupportTicket();
        ticket.setCustomer(customer);
        ticket.setFacility(facility);
        ticket.setSubject(request.subject().trim());
        ticket.setDescription(request.description().trim());
        ticket.setStatus(SupportTicketStatus.open);
        SupportTicket saved = ticketRepository.saveAndFlush(ticket);
        addMessage(saved, customer, request.description());

        auditLogService.recordMutation(
            "SUPPORT_TICKET_CREATED", "SupportTicket", saved.getId(),
            facility != null ? facility.getId() : null, null,
            Map.of("status", saved.getStatus(), "subject", saved.getSubject())
        );
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SupportTicketResponse getCustomer(ActorPrincipal actor, UUID ticketId) {
        requireCustomer(actor);
        SupportTicket ticket = ticketRepository.findById(ticketId)
            .filter(item -> item.getCustomer().getId().equals(actor.userId()))
            .orElseThrow(() -> ApiExceptions.notFound("Support ticket was not found"));
        return toResponse(ticket);
    }

    @Transactional
    public SupportTicketResponse addCustomerMessage(
        ActorPrincipal actor,
        UUID ticketId,
        CreateSupportMessageRequest request
    ) {
        requireCustomer(actor);
        SupportTicket ticket = ticketRepository.findById(ticketId)
            .filter(item -> item.getCustomer().getId().equals(actor.userId()))
            .orElseThrow(() -> ApiExceptions.notFound("Support ticket was not found"));
        if (ticket.getStatus() == SupportTicketStatus.closed) {
            throw ApiExceptions.conflict("Closed support tickets cannot receive new messages");
        }
        User customer = userRepository.getReferenceById(actor.userId());
        addMessage(ticket, customer, request.body());
        if (ticket.getStatus() == SupportTicketStatus.resolved) {
            ticket.setStatus(SupportTicketStatus.open);
            ticketRepository.save(ticket);
        }
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public PageResponse<SupportTicketResponse> listStaff(
        ActorPrincipal actor,
        SupportTicketStatus status,
        UUID facilityId,
        String query,
        int page,
        int size,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_SUPPORT);
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Customer actors cannot access the staff support queue");
        }
        if (facilityId != null) facilityScopeService.assertCanRead(actor, facilityId);
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        Collection<UUID> allowedFacilityIds = scoped
            ? actor.facilityScopes().keySet()
            : List.of(new UUID(0, 0));
        var result = ticketRepository.searchForStaff(
            status, facilityId, scoped, allowedFacilityIds,
            query == null || query.isBlank() ? null : query.trim(), pageRequest(page, size)
        );
        return PageResponse.from(result.map(this::toResponse), correlationId);
    }

    @Transactional
    public SupportTicketResponse updateByStaff(
        ActorPrincipal actor,
        UUID ticketId,
        UpdateSupportTicketRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_SUPPORT);
        SupportTicket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> ApiExceptions.notFound("Support ticket was not found"));
        if (ticket.getFacility() != null) {
            facilityScopeService.assertCanOperate(actor, ticket.getFacility().getId());
        }

        User assignee = request.assignedToId() == null
            ? ticket.getAssignedTo()
            : findActiveStaff(request.assignedToId());
        if (assignee != null && ticket.getFacility() != null) {
            facilityScopeService.assertCanRead(actor, ticket.getFacility().getId());
        }
        SupportTicketStatus previousStatus = ticket.getStatus();
        ticket.setStatus(request.status());
        ticket.setAssignedTo(assignee);
        SupportTicket saved = ticketRepository.saveAndFlush(ticket);
        if (request.message() != null && !request.message().isBlank()) {
            addMessage(saved, userRepository.getReferenceById(actor.userId()), request.message());
        }

        auditLogService.recordMutation(
            "SUPPORT_TICKET_UPDATED", "SupportTicket", saved.getId(),
            saved.getFacility() != null ? saved.getFacility().getId() : null,
            Map.of("status", previousStatus),
            Map.of("status", saved.getStatus(), "assignedToId", saved.getAssignedTo() != null ? saved.getAssignedTo().getId() : "")
        );
        return toResponse(saved);
    }

    private void requireCustomer(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.VIEW_SUPPORT);
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customer actors can access customer support tickets");
        }
    }

    private Facility resolveFacilityForCustomer(ActorPrincipal actor, UUID facilityId) {
        if (facilityId == null) return null;
        Facility facility = facilityRepository.findById(facilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        facilityScopeService.assertCanRead(actor, facilityId);
        return facility;
    }

    private User findActiveStaff(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiExceptions.notFound("Staff account was not found"));
        if (user.getStatus() != UserStatus.ACTIVE
            || user.getRoles().stream().noneMatch(role -> role.getCode() == RoleCode.STAFF)) {
            throw ApiExceptions.validation("assignedToId must refer to an active Staff account", userId);
        }
        return user;
    }

    private void addMessage(SupportTicket ticket, User author, String body) {
        SupportTicketMessage message = new SupportTicketMessage();
        message.setTicket(ticket);
        message.setAuthor(author);
        message.setBody(body.trim());
        messageRepository.save(message);
    }

    private SupportTicketResponse toResponse(SupportTicket ticket) {
        List<SupportTicketResponse.Message> messages = messageRepository
            .findByTicket_IdOrderByCreatedAtAsc(ticket.getId())
            .stream()
            .map(message -> new SupportTicketResponse.Message(
                message.getId(),
                message.getAuthor().getId(),
                message.getAuthor().getFullName(),
                message.getAuthor().getId().equals(ticket.getCustomer().getId()),
                message.getBody(),
                message.getCreatedAt()
            ))
            .toList();
        return new SupportTicketResponse(
            ticket.getId(),
            ticket.getCustomer().getId(),
            ticket.getCustomer().getFullName(),
            ticket.getCustomer().getEmail(),
            ticket.getFacility() != null ? ticket.getFacility().getId() : null,
            ticket.getFacility() != null ? ticket.getFacility().getName() : null,
            ticket.getAssignedTo() != null ? ticket.getAssignedTo().getId() : null,
            ticket.getAssignedTo() != null ? ticket.getAssignedTo().getFullName() : null,
            ticket.getStatus(),
            ticket.getSubject(),
            ticket.getDescription(),
            messages,
            ticket.getCreatedAt(),
            ticket.getUpdatedAt()
        );
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
    }
}
