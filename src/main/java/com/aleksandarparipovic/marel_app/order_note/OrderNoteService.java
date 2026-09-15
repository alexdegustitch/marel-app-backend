package com.aleksandarparipovic.marel_app.order_note;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.order_note.dto.OrderNoteDto;
import com.aleksandarparipovic.marel_app.order_note.dto.OrderNoteRequest;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * The rules of the wall, in one place for both kinds of order.
 *
 * <p><b>Who may do what.</b> Everyone signed in may WRITE — that is the point,
 * the wall is the company's. The AUTHOR may edit and delete their own note; an
 * administrator, director or developer may moderate ANY note. The URL layer only
 * checks the reader is signed in ({@code authenticated()} in SecurityConfig);
 * every finer rule is decided here, where it can read the note's author.
 *
 * <p><b>Deleting is archiving.</b> A note the floor already acted on is never
 * silently gone: delete flips it inactive and stamps {@code archived_at}, and the
 * lists only ever read live notes.
 */
@Service
@RequiredArgsConstructor
public class OrderNoteService {

    /** The roles that moderate the wall — Direktor, Administrator, developer. */
    private static final Set<String> MODERATOR_ROLES = Set.of("admin", "supervisor", "developer");

    /**
     * Bridges the HTTP body — plain lists and maps — to the Jackson tree Hibernate
     * stores in jsonb, and back. Local and default-configured on purpose: the
     * application runs on Jackson 3 for HTTP while Hibernate's JSON mapping is
     * Jackson 2, and this is the one seam that has to speak the latter.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrderNoteRepository orderNoteRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final SampleOrderRepository sampleOrderRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    /**
     * One order's notes, narrowed and ordered BY THE SERVER: {@code q} searches
     * the flattened text, {@code authorId} keeps one colleague's, and the page
     * window and sort ride in on the {@code Pageable}. The parent is checked to
     * exist first, so a note list for an order that is not there is a 404 rather
     * than an empty page.
     */
    @Transactional(readOnly = true)
    public Page<OrderNoteDto> list(
            OrderNoteParentType type,
            Long orderId,
            String q,
            Long authorId,
            Pageable pageable
    ) {
        requireParent(type, orderId);
        User viewer = currentUserOrNull();

        Specification<OrderNote> spec = OrderNoteSpecifications.onParent(type, orderId)
                .and(OrderNoteSpecifications.active());
        if (authorId != null) {
            spec = spec.and(OrderNoteSpecifications.byAuthor(authorId));
        }
        if (q != null && !q.isBlank()) {
            spec = spec.and(OrderNoteSpecifications.matchesText(q.trim()));
        }

        return orderNoteRepository.findAll(spec, pageable).map(note -> toDto(note, viewer));
    }

    /** The live-note count for the "Beleške (N)" badge. */
    @Transactional(readOnly = true)
    public long count(OrderNoteParentType type, Long orderId) {
        requireParent(type, orderId);
        return switch (type) {
            case PRODUCTION -> orderNoteRepository.countByProductionOrder_IdAndIsActiveIsTrue(orderId);
            case SAMPLE -> orderNoteRepository.countBySampleOrder_IdAndIsActiveIsTrue(orderId);
        };
    }

    @Transactional
    public OrderNoteDto create(OrderNoteParentType type, Long orderId, OrderNoteRequest request) {
        User author = requireCurrentUser();
        String bodyText = requireNonEmptyBody(request);

        OrderNote note = OrderNote.builder()
                .author(author)
                .bodyJson(toJsonNode(request.body()))
                .bodyText(bodyText)
                .isActive(true)
                .build();

        switch (type) {
            case PRODUCTION -> note.setProductionOrder(requireProductionOrder(orderId));
            case SAMPLE -> note.setSampleOrder(requireSampleOrder(orderId));
        }

        return toDto(orderNoteRepository.save(note), author);
    }

    @Transactional
    public OrderNoteDto update(
            OrderNoteParentType type,
            Long orderId,
            Long noteId,
            OrderNoteRequest request
    ) {
        User viewer = requireCurrentUser();
        OrderNote note = requireNote(type, orderId, noteId);
        if (!canModify(note, viewer)) {
            throw new AccessDeniedException("Ovu belešku može da izmeni samo njen autor ili administrator.");
        }

        String bodyText = requireNonEmptyBody(request);
        note.setBodyJson(toJsonNode(request.body()));
        note.setBodyText(bodyText);

        return toDto(orderNoteRepository.save(note), viewer);
    }

    @Transactional
    public void delete(OrderNoteParentType type, Long orderId, Long noteId) {
        User viewer = requireCurrentUser();
        OrderNote note = requireNote(type, orderId, noteId);
        if (!canModify(note, viewer)) {
            throw new AccessDeniedException("Ovu belešku može da obriše samo njen autor ili administrator.");
        }

        note.setIsActive(false);
        note.setArchivedAt(OffsetDateTime.now());
        orderNoteRepository.save(note);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private OrderNoteDto toDto(OrderNote note, User viewer) {
        boolean mayModify = canModify(note, viewer);
        return new OrderNoteDto(
                note.getId(),
                note.getAuthor().getId(),
                authorName(note.getAuthor()),
                fromJsonNode(note.getBodyJson()),
                note.getBodyText(),
                note.getCreatedAt(),
                note.getUpdatedAt(),
                note.getUpdatedAt() != null,
                mayModify,
                mayModify
        );
    }

    private static String authorName(User user) {
        String display = user.getDisplayName();
        if (display != null && !display.isBlank()) {
            return display;
        }
        return user.getFullName();
    }

    /** The HTTP body (lists and maps) as the Jackson tree Hibernate persists. */
    private static JsonNode toJsonNode(Object body) {
        return JSON.valueToTree(body);
    }

    /** The persisted tree back as lists and maps, for the HTTP layer to serialise. */
    private static Object fromJsonNode(JsonNode node) {
        return JSON.convertValue(node, Object.class);
    }

    // ── rules ────────────────────────────────────────────────────────────────

    /** The author, or anyone who moderates the wall. */
    private boolean canModify(OrderNote note, User viewer) {
        if (viewer == null) {
            return false;
        }
        if (note.getAuthor().getId().equals(viewer.getId())) {
            return true;
        }
        return isModerator(viewer);
    }

    private boolean isModerator(User user) {
        return user.getRole() != null && MODERATOR_ROLES.contains(user.getRole().getRoleName());
    }

    private String requireNonEmptyBody(OrderNoteRequest request) {
        String bodyText = NoteText.flatten(request.body());
        if (bodyText.isBlank()) {
            throw new IllegalArgumentException("Beleška ne može biti prazna.");
        }
        return bodyText;
    }

    // ── loading ──────────────────────────────────────────────────────────────

    /**
     * The note, but only if it hangs off THIS order. Loading by id alone would
     * let an edit on order A reach a note that belongs to order B; the parent
     * check closes that. An archived note reads as gone.
     */
    private OrderNote requireNote(OrderNoteParentType type, Long orderId, Long noteId) {
        OrderNote note = orderNoteRepository.findById(noteId)
                .orElseThrow(() -> new EntityNotFoundException("Beleška nije pronađena (id=" + noteId + ")"));

        boolean belongs = switch (type) {
            case PRODUCTION -> note.getProductionOrder() != null
                    && note.getProductionOrder().getId().equals(orderId);
            case SAMPLE -> note.getSampleOrder() != null
                    && note.getSampleOrder().getId().equals(orderId);
        };
        if (!belongs || !Boolean.TRUE.equals(note.getIsActive())) {
            throw new EntityNotFoundException("Beleška nije pronađena (id=" + noteId + ")");
        }
        return note;
    }

    private void requireParent(OrderNoteParentType type, Long orderId) {
        switch (type) {
            case PRODUCTION -> requireProductionOrder(orderId);
            case SAMPLE -> requireSampleOrder(orderId);
        }
    }

    private ProductionOrder requireProductionOrder(Long orderId) {
        return productionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Proizvodni nalog nije pronađen (id=" + orderId + ")"));
    }

    private SampleOrder requireSampleOrder(Long orderId) {
        return sampleOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Nalog za uzorak nije pronađen (id=" + orderId + ")"));
    }

    private User requireCurrentUser() {
        User user = currentUserOrNull();
        if (user == null) {
            throw new AccessDeniedException("Radnja zahteva prijavljenog korisnika.");
        }
        return user;
    }

    private User currentUserOrNull() {
        Long userId = currentUserService.getCurrentUserId();
        return userId == null ? null : userRepository.findById(userId).orElse(null);
    }
}
