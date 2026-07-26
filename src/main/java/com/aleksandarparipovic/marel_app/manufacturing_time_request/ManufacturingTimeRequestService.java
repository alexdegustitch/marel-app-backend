package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeService;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The manufacturing-time request workflow.
 *
 * <p>Two invariants this service exists to protect:
 * <ol>
 *   <li>Completing a request and producing its manufacturing-time result happen in
 *       ONE transaction. A failure while producing the result must never leave a
 *       COMPLETED request with nothing to show for it.</li>
 *   <li>A request is owned before it is decided, so responsibility for every
 *       outcome is attributable.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ManufacturingTimeRequestService {

    private final ManufacturingTimeRequestRepository requestRepository;
    private final ProductRepository productRepository;
    private final ProductManufacturingTimeRepository manufacturingTimeRepository;
    private final ProductManufacturingTimeService manufacturingTimeService;
    private final UserRepository userRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionService permissionService;

    private static final List<ManufacturingTimeRequestStatus> OPEN_STATUSES =
            List.of(ManufacturingTimeRequestStatus.PENDING, ManufacturingTimeRequestStatus.IN_REVIEW);

    @Transactional
    public ManufacturingTimeRequestResponse create(
            ManufacturingTimeRequestCreateRequest request, Long requesterId
    ) {
        User requester = loadUser(requesterId);
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Proizvod nije pronađen: " + request.getProductId()));

        ProductManufacturingTime target = resolveTarget(request, product);

        ManufacturingTimeRequest saved = requestRepository.save(
                ManufacturingTimeRequest.builder()
                        .product(product)
                        .createdBy(requester)
                        .requestType(request.getRequestType())
                        .description(request.getDescription().trim())
                        .targetManufacturingTime(target)
                        .status(ManufacturingTimeRequestStatus.PENDING)
                        .build()
        );

        outboxEventPublisher.publish(
                OutboxEventType.MANUFACTURING_TIME_REQUEST_CREATED,
                OutboxAggregateType.MANUFACTURING_TIME_REQUEST,
                saved.getId(),
                payloadFor(saved)
        );

        return toResponse(saved);
    }

    /**
     * Validates the target against the request type and the product it belongs to.
     * A target from a different product would let a request quietly rewrite an
     * unrelated record.
     */
    private ProductManufacturingTime resolveTarget(
            ManufacturingTimeRequestCreateRequest request, Product product
    ) {
        Long targetId = request.getTargetManufacturingTimeId();

        if (!request.getRequestType().requiresTarget()) {
            if (targetId != null) {
                throw new IllegalArgumentException(
                        "Zahtev tipa CREATE ne sme da ima ciljni zapis vremena izrade.");
            }
            return null;
        }

        if (targetId == null) {
            throw new IllegalArgumentException(
                    "Za tip zahteva " + request.getRequestType() + " ciljni zapis je obavezan.");
        }

        ProductManufacturingTime target = manufacturingTimeService.getActiveOrThrow(targetId);

        if (!target.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException(
                    "Ciljni zapis vremena izrade ne pripada izabranom proizvodu.");
        }

        if (requestRepository.existsByTargetManufacturingTime_IdAndStatusIn(targetId, OPEN_STATUSES)) {
            throw new ConflictException(
                    "Za ovaj zapis vremena izrade već postoji otvoren zahtev.");
        }

        return target;
    }

    /**
     * Claim or assign. Locks the row first so two processors cannot both believe
     * they own it; the loser waits, re-reads, and sees it is already IN_REVIEW.
     */
    @Transactional
    public ManufacturingTimeRequestResponse assign(
            Long requestId, Long actorId, Long assigneeUserId
    ) {
        ManufacturingTimeRequest request = loadForUpdate(requestId);
        User assignee = loadUser(assigneeUserId == null ? actorId : assigneeUserId);

        if (request.getStatus() == ManufacturingTimeRequestStatus.IN_REVIEW) {
            // Reassignment of work somebody already owns is a deliberate act, not
            // a silent takeover.
            request.reassignTo(assignee);
        } else {
            request.assignTo(assignee);
        }

        outboxEventPublisher.publish(
                OutboxEventType.MANUFACTURING_TIME_REQUEST_ASSIGNED,
                OutboxAggregateType.MANUFACTURING_TIME_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request);
    }

    @Transactional
    public ManufacturingTimeRequestResponse release(Long requestId, Long actorId) {
        ManufacturingTimeRequest request = loadForUpdate(requestId);
        requireAssigneeOrProcessor(request, actorId);
        request.release();
        return toResponse(request);
    }

    /**
     * Completes the request AND produces its manufacturing-time result, atomically.
     *
     * <p>Both writes are in this one transaction: if producing the result throws,
     * the completion rolls back with it and the request stays IN_REVIEW.
     */
    @Transactional
    public ManufacturingTimeRequestResponse complete(
            Long requestId, Long processorId, ManufacturingTimeRequestDecisionRequest decision
    ) {
        ManufacturingTimeRequest request = loadForUpdate(requestId);
        User processor = loadUser(processorId);

        requireNotOwnRequest(request, processorId);
        requireAssigneeOrProcessor(request, processorId);

        request.complete(processor, decision == null ? null : decision.getDecisionNote());

        ProductManufacturingTime result = produceResult(request, processor, decision);
        result.setSourceRequest(request);

        outboxEventPublisher.publish(
                OutboxEventType.MANUFACTURING_TIME_REQUEST_COMPLETED,
                OutboxAggregateType.MANUFACTURING_TIME_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request);
    }

    private ProductManufacturingTime produceResult(
            ManufacturingTimeRequest request,
            User processor,
            ManufacturingTimeRequestDecisionRequest decision
    ) {
        return switch (request.getRequestType()) {
            case CREATE -> {
                if (decision == null || decision.getManufacturingTime() == null) {
                    throw new IllegalArgumentException(
                            "Za završetak CREATE zahteva potrebni su podaci o vremenu izrade.");
                }
                // The product comes from the request, not the payload — a processor
                // must not be able to redirect the result to a different product.
                decision.getManufacturingTime().setProductId(request.getProduct().getId());
                yield manufacturingTimeService.createForUser(decision.getManufacturingTime(), processor);
            }
            case UPDATE, RECALCULATE -> {
                ProductManufacturingTimeUpdateRequest update =
                        decision == null ? null : decision.getManufacturingTimeUpdate();
                if (update == null) {
                    throw new IllegalArgumentException(
                            "Za završetak ovog zahteva potrebni su izmenjeni podaci o vremenu izrade.");
                }
                yield manufacturingTimeService.applyUpdate(
                        request.getTargetManufacturingTime().getId(), update);
            }
            case DEACTIVATE -> {
                Long targetId = request.getTargetManufacturingTime().getId();
                manufacturingTimeService.delete(targetId);
                yield manufacturingTimeRepository.findById(targetId).orElseThrow();
            }
        };
    }

    /** Declines without producing any result. */
    @Transactional
    public ManufacturingTimeRequestResponse decline(
            Long requestId, Long processorId, String note
    ) {
        ManufacturingTimeRequest request = loadForUpdate(requestId);
        User processor = loadUser(processorId);

        requireNotOwnRequest(request, processorId);
        requireAssigneeOrProcessor(request, processorId);

        request.decline(processor, note);

        outboxEventPublisher.publish(
                OutboxEventType.MANUFACTURING_TIME_REQUEST_DECLINED,
                OutboxAggregateType.MANUFACTURING_TIME_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request);
    }

    /** The requester withdraws their own still-open request. */
    @Transactional
    public ManufacturingTimeRequestResponse cancel(Long requestId, Long actorId, String note) {
        ManufacturingTimeRequest request = loadForUpdate(requestId);

        boolean isRequester = request.getCreatedBy().getId().equals(actorId);
        boolean mayProcess =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS);

        if (!isRequester && !mayProcess) {
            throw new AccessDeniedException("Nije dozvoljeno otkazivanje tuđeg zahteva.");
        }

        request.cancel(note);
        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public Page<ManufacturingTimeRequestResponse> search(
            ManufacturingTimeRequestStatus status,
            Long productId,
            Long createdById,
            Long assignedToId,
            Pageable pageable
    ) {
        return requestRepository
                .search(status, productId, createdById, assignedToId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ManufacturingTimeRequestResponse getById(Long requestId) {
        return toResponse(loadDetail(requestId));
    }

    /**
     * Submitting and deciding are separate responsibilities. No current role rule
     * grants an exception, so a requester never processes their own request.
     */
    private void requireNotOwnRequest(ManufacturingTimeRequest request, Long actorId) {
        if (request.getCreatedBy().getId().equals(actorId)) {
            throw new AccessDeniedException("Ne možete da obradite sopstveni zahtev.");
        }
    }

    /**
     * Only the current owner decides. Anyone else with the process permission must
     * take ownership first, so assigned_to always matches who did the work.
     */
    private void requireAssigneeOrProcessor(ManufacturingTimeRequest request, Long actorId) {
        User assignee = request.getAssignedTo();
        if (assignee == null || !assignee.getId().equals(actorId)) {
            throw new AccessDeniedException(
                    "Zahtev mora prvo da bude dodeljen vama da biste ga obradili.");
        }
    }

    private ManufacturingTimeRequest loadForUpdate(Long requestId) {
        requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Zahtev nije pronađen: " + requestId));
        // Re-read through the fetch-joined query so the response can be built
        // without lazy-loading surprises; the row lock above is already held.
        return loadDetail(requestId);
    }

    private ManufacturingTimeRequest loadDetail(Long requestId) {
        return requestRepository.findDetailById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Zahtev nije pronađen: " + requestId));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    private Map<String, Object> payloadFor(ManufacturingTimeRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("requestType", request.getRequestType().name());
        payload.put("productId", request.getProduct().getId());
        payload.put("productName", request.getProduct().getProductName());
        payload.put("createdByUserId", request.getCreatedBy().getId());
        if (request.getAssignedTo() != null) {
            payload.put("assignedToUserId", request.getAssignedTo().getId());
        }
        return payload;
    }

    private ManufacturingTimeRequestResponse toResponse(ManufacturingTimeRequest r) {
        Long resultId = manufacturingTimeRepository.findBySourceRequest_Id(r.getId())
                .map(ProductManufacturingTime::getId)
                .orElse(null);

        return new ManufacturingTimeRequestResponse(
                r.getId(),
                r.getProduct().getId(),
                r.getProduct().getProductName(),
                r.getRequestType(),
                r.getDescription(),
                r.getStatus(),
                r.getCreatedBy().getId(),
                r.getCreatedBy().getFullName(),
                r.getAssignedTo() == null ? null : r.getAssignedTo().getId(),
                r.getAssignedTo() == null ? null : r.getAssignedTo().getFullName(),
                r.getProcessedBy() == null ? null : r.getProcessedBy().getId(),
                r.getProcessedBy() == null ? null : r.getProcessedBy().getFullName(),
                r.getProcessedAt(),
                r.getDecisionNote(),
                r.getTargetManufacturingTime() == null ? null : r.getTargetManufacturingTime().getId(),
                resultId,
                r.getCancelledAt(),
                r.getCreatedAt()
        );
    }
}
