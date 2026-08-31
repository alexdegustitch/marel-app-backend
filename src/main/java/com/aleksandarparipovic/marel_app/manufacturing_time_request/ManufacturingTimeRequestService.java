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
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import com.aleksandarparipovic.marel_app.sample_order_line_item.repository.SampleOrderLineItemRepository;
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
    private final ProductionOrderLineItemRepository lineItemRepository;
    private final SampleOrderLineItemRepository sampleLineItemRepository;
    private final ProductManufacturingTimeRepository manufacturingTimeRepository;
    private final ProductManufacturingTimeService manufacturingTimeService;
    private final UserRepository userRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionService permissionService;

    /**
     * The statuses that still say something about an order line: work under way,
     * or an answer that stands. A refused or withdrawn request leaves no trace on
     * the line it was raised from.
     */
    private static final List<ManufacturingTimeRequestStatus> LIVE_STATUSES =
            List.of(ManufacturingTimeRequestStatus.PENDING,
                    ManufacturingTimeRequestStatus.IN_REVIEW,
                    ManufacturingTimeRequestStatus.COMPLETED);

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
        requireAtMostOneOccasion(request);
        ProductionOrderLineItem lineItem = resolveLineItem(request, product);
        SampleOrderLineItem sampleLineItem = resolveSampleLineItem(request, product);

        ManufacturingTimeRequest saved = requestRepository.save(
                ManufacturingTimeRequest.builder()
                        .product(product)
                        .createdBy(requester)
                        .requestType(request.getRequestType())
                        .description(request.getDescription().trim())
                        .targetManufacturingTime(target)
                        .productionOrderLineItem(lineItem)
                        .sampleOrderLineItem(sampleLineItem)
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
     * A request has ONE occasion, or none.
     *
     * <p>Checked before either line is loaded, so the refusal costs nothing and
     * says the one thing that is wrong. The database states the same rule
     * ({@code chk_manufacturing_time_requests_single_occasion}), but as a
     * constraint violation — which tells the caller that something failed, not
     * which two fields disagree.
     */
    private static void requireAtMostOneOccasion(ManufacturingTimeRequestCreateRequest request) {
        if (request.getProductionOrderLineItemId() != null
                && request.getSampleOrderLineItemId() != null) {
            throw new IllegalArgumentException(
                    "Zahtev može da bude vezan za stavku proizvodnog naloga ILI za stavku "
                            + "naloga za uzorke, ne za obe.");
        }
    }

    /**
     * Resolves the sample-order line the request was raised on.
     *
     * <p>Same three rules as the production-order line below, for the same
     * reasons: the line must still be live, its product and the request's must be
     * the same one, and a line that already has an open request does not get a
     * second. Written out rather than shared, because the two speak about
     * different documents and the messages have to name the right one — "stavka
     * proizvodnog naloga" on a sample order would send somebody looking in the
     * wrong list.
     */
    private SampleOrderLineItem resolveSampleLineItem(
            ManufacturingTimeRequestCreateRequest request, Product product
    ) {
        Long lineItemId = request.getSampleOrderLineItemId();
        if (lineItemId == null) {
            return null;
        }

        SampleOrderLineItem lineItem = sampleLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Stavka naloga za uzorke nije pronađena: " + lineItemId));

        if (!Boolean.TRUE.equals(lineItem.getIsActive())) {
            throw new ConflictException(
                    "Stavka naloga za uzorke više nije aktivna, pa zahtev ne može da se veže za nju.");
        }

        if (!lineItem.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException(
                    "Izabrana stavka naloga za uzorke ne pripada izabranom proizvodu.");
        }

        if (requestRepository.existsBySampleOrderLineItem_IdAndStatusIn(lineItemId, OPEN_STATUSES)) {
            throw new ConflictException(
                    "Za ovu stavku naloga za uzorke već postoji otvoren zahtev.");
        }

        return lineItem;
    }

    /**
     * Resolves the production-order line the request was raised on.
     *
     * <p>The line is the occasion, so it is optional. When it is given, the
     * product it carries and the product the request names must be the same one:
     * a mismatch is refused rather than resolved, because either half could be
     * the client's mistake and silently preferring one would produce a request
     * about a product nobody asked for. The database enforces the same pairing
     * through {@code fk_manufacturing_time_requests_line_item}; this check exists
     * to say so in words a user can act on.
     */
    private ProductionOrderLineItem resolveLineItem(
            ManufacturingTimeRequestCreateRequest request, Product product
    ) {
        Long lineItemId = request.getProductionOrderLineItemId();
        if (lineItemId == null) {
            return null;
        }

        ProductionOrderLineItem lineItem = lineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Stavka proizvodnog naloga nije pronađena: " + lineItemId));

        if (!Boolean.TRUE.equals(lineItem.getIsActive())) {
            throw new ConflictException(
                    "Stavka proizvodnog naloga više nije aktivna, pa zahtev ne može da se veže za nju.");
        }

        if (!lineItem.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException(
                    "Izabrana stavka proizvodnog naloga ne pripada izabranom proizvodu.");
        }

        if (requestRepository.existsByProductionOrderLineItem_IdAndStatusIn(lineItemId, OPEN_STATUSES)) {
            throw new ConflictException(
                    "Za ovu stavku proizvodnog naloga već postoji otvoren zahtev.");
        }

        return lineItem;
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
        claimIfUnowned(request, processor);
        requireAssigneeOrProcessor(request, processorId);
        // Refuse an illegal completion before anything is written.
        request.requireCompletable();

        ProductManufacturingTime result = resolveResult(request, processor, decision);
        request.complete(processor, decision == null ? null : decision.getDecisionNote(), result);

        outboxEventPublisher.publish(
                OutboxEventType.MANUFACTURING_TIME_REQUEST_COMPLETED,
                OutboxAggregateType.MANUFACTURING_TIME_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request);
    }

    /**
     * The record that will answer the request: either one that already exists, or
     * one this completion produces.
     */
    private ProductManufacturingTime resolveResult(
            ManufacturingTimeRequest request,
            User processor,
            ManufacturingTimeRequestDecisionRequest decision
    ) {
        Long existingId = decision == null ? null : decision.getExistingManufacturingTimeId();

        return existingId == null
                ? produceResult(request, processor, decision)
                : attachExisting(request, existingId, decision.getManufacturingTimeUpdate());
    }

    /**
     * Answers the request with a manufacturing time that already exists.
     *
     * <p>Nothing is produced, so {@code sourceRequest} is deliberately NOT
     * stamped: this request did not write that record, and overwriting the
     * stamp would take authorship away from the request that did — and, with it,
     * that request's own result.
     *
     * <p>The record may already answer other requests. That is the whole point:
     * one manufacturing time settles everyone who asked for the same product's.
     */
    private ProductManufacturingTime attachExisting(
            ManufacturingTimeRequest request,
            Long existingId,
            ProductManufacturingTimeUpdateRequest update
    ) {
        if (request.getRequestType().requiresTarget()) {
            throw new IllegalArgumentException(
                    "Zahtev tipa " + request.getRequestType()
                            + " se zavrsava nad ciljnim zapisom, pa ne moze da se veze za drugi.");
        }

        ProductManufacturingTime existing = manufacturingTimeService.getActiveOrThrow(existingId);

        if (!existing.getProduct().getId().equals(request.getProduct().getId())) {
            throw new IllegalArgumentException(
                    "Izabrano vreme izrade ne pripada proizvodu iz zahteva.");
        }

        if (update == null) {
            return existing;
        }

        // Changing the record and settling the request are one act, so they are
        // one transaction: a processor who reworked the numbers must never end up
        // with the record rewritten and the request still open.
        ProductManufacturingTime changed = manufacturingTimeService.applyUpdate(existingId, update);
        // Reworking IS authorship, unlike plain attaching.
        changed.setSourceRequest(request);
        return changed;
    }

    private ProductManufacturingTime produceResult(
            ManufacturingTimeRequest request,
            User processor,
            ManufacturingTimeRequestDecisionRequest decision
    ) {
        // Every branch below WRITES the record, so every branch stamps
        // authorship on it.
        ProductManufacturingTime produced = switch (request.getRequestType()) {
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

        produced.setSourceRequest(request);
        return produced;
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
            Long productionOrderId,
            Long mineUserId,
            java.time.OffsetDateTime createdFrom,
            java.time.OffsetDateTime createdTo,
            Pageable pageable
    ) {
        return requestRepository
                .search(status, productId, createdById, assignedToId, productionOrderId,
                        mineUserId,
                        ManufacturingTimeRequestStatus.PENDING,
                        ManufacturingTimeRequestStatus.IN_REVIEW,
                        ManufacturingTimeRequestStatus.COMPLETED,
                        ManufacturingTimeRequestStatus.DECLINED,
                        createdFrom, createdTo,
                        pageable)
                .map(this::toResponse);
    }

    /**
     * What this person can pick up on the manufacturing-time screen: requests
     * still waiting for anyone, plus the ones they have already taken.
     * {@code restrictToCreatedById} is the caller's own id when they may not read
     * everybody's requests, and NULL when they may.
     */
    @Transactional(readOnly = true)
    public List<ManufacturingTimeRequestResponse> pickableRequests(
            Long actorId, Long restrictToCreatedById
    ) {
        return requestRepository.findPickable(
                        ManufacturingTimeRequestStatus.PENDING,
                        ManufacturingTimeRequestStatus.IN_REVIEW,
                        actorId,
                        restrictToCreatedById)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * What each of one order's lines can say about its manufacturing time: a
     * request still running, or the answer it already got.
     * {@code restrictToCreatedById} is the caller's own id when they may not read
     * everybody's requests, and NULL when they may.
     */
    @Transactional(readOnly = true)
    public List<ManufacturingTimeRequestResponse> forProductionOrder(
            Long productionOrderId, Long restrictToCreatedById
    ) {
        return requestRepository
                .findByProductionOrderAndStatusIn(
                        productionOrderId, LIVE_STATUSES, restrictToCreatedById)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The same, for a sample order's lines.
     * {@code restrictToCreatedById} is the caller's own id when they may not read
     * everybody's requests, and NULL when they may.
     */
    @Transactional(readOnly = true)
    public List<ManufacturingTimeRequestResponse> forSampleOrder(
            Long sampleOrderId, Long restrictToCreatedById
    ) {
        return requestRepository
                .findBySampleOrderAndStatusIn(sampleOrderId, LIVE_STATUSES, restrictToCreatedById)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ManufacturingTimeRequestResponse getById(Long requestId) {
        return toResponse(loadDetail(requestId));
    }

    /**
     * Takes an unowned request on the way to completing it.
     *
     * <p>Completing is done from the manufacturing-time screen, where the
     * processor picks a request and hands it a result in one motion; making them
     * walk back to the queue to press "claim" first would be ceremony, not
     * safety. This is still a claim-and-process sequence rather than a blind
     * write: {@code assigned_to} ends up naming whoever did the work, which is
     * the whole point of the rule.
     *
     * <p>A request somebody ELSE owns is deliberately not touched — the check
     * below then refuses it, and taking work away from a colleague stays a
     * deliberate reassignment.
     */
    private void claimIfUnowned(ManufacturingTimeRequest request, User processor) {
        if (request.getStatus() == ManufacturingTimeRequestStatus.PENDING
                && request.getAssignedTo() == null) {
            request.assignTo(processor);
        }
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
        if (request.getProductionOrderLineItem() != null) {
            var order = request.getProductionOrderLineItem().getProductionOrder();
            payload.put("productionOrderId", order.getId());
            payload.put("productionOrderCode", order.getCode());
            payload.put("productionOrderName", order.getName());
        }
        if (request.getSampleOrderLineItem() != null) {
            var order = request.getSampleOrderLineItem().getSampleOrder();
            payload.put("sampleOrderId", order.getId());
            payload.put("sampleOrderCode", order.getCode());
            payload.put("sampleOrderName", order.getName());
        }
        if (request.getAssignedTo() != null) {
            payload.put("assignedToUserId", request.getAssignedTo().getId());
        }
        return payload;
    }

    private ManufacturingTimeRequestResponse toResponse(ManufacturingTimeRequest r) {
        // Read from the request's own column rather than looking the record up by
        // source_request_id: that lookup was one query PER ROW of every list, and
        // it answered the wrong question now that a record can answer many
        // requests.
        ProductManufacturingTime result = r.getResultManufacturingTime();
        ProductionOrderLineItem lineItem = r.getProductionOrderLineItem();
        SampleOrderLineItem sampleLineItem = r.getSampleOrderLineItem();

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
                lineItem == null ? null : lineItem.getId(),
                lineItem == null ? null : lineItem.getProductionOrder().getId(),
                lineItem == null ? null : lineItem.getProductionOrder().getCode(),
                lineItem == null ? null : lineItem.getProductionOrder().getName(),
                lineItem == null ? null : lineItem.getProductDescription(),
                sampleLineItem == null ? null : sampleLineItem.getId(),
                sampleLineItem == null ? null : sampleLineItem.getSampleOrder().getId(),
                sampleLineItem == null ? null : sampleLineItem.getSampleOrder().getCode(),
                sampleLineItem == null ? null : sampleLineItem.getSampleOrder().getName(),
                sampleLineItem == null ? null : sampleLineItem.getProductDescription(),
                result == null ? null : result.getId(),
                result == null ? null : result.getManufacturingTimeSeconds(),
                result == null ? null : result.getProductsPerHour(),
                r.getCancelledAt(),
                r.getCreatedAt()
        );
    }
}
