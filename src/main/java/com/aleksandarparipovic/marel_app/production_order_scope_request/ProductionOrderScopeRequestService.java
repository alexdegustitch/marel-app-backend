package com.aleksandarparipovic.marel_app.production_order_scope_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.repository.ProductionOrderLineItemNoteRepository;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.*;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The order-scope request workflow: commercial staff ask which operations an
 * order really needs, the shop floor answers, and that answer becomes what the
 * order's progress is measured against.
 *
 * <p>Three invariants this service exists to protect:
 * <ol>
 *   <li>The set of covered lines is fixed when the request is RAISED. A processor
 *       decides what is needed on those lines, never which lines are in scope —
 *       otherwise the answer would stop being an answer to the question asked.</li>
 *   <li>Saving and submitting write the same payload the same way, so a
 *       processor who saved and then submitted hands over exactly what they were
 *       looking at.</li>
 *   <li>A request is owned before it is decided, so responsibility for every
 *       outcome is attributable.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderScopeRequestService {

    private final ProductionOrderScopeRequestRepository requestRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderLineItemRepository lineItemRepository;
    private final ProductionOrderLineItemNoteRepository lineItemNoteRepository;
    private final OperationRepository operationRepository;
    private final UserRepository userRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionService permissionService;

    /**
     * The statuses that still say something about an order line: work under way,
     * or an answer that stands. A refused or withdrawn request leaves the line
     * exactly as it was.
     */
    /**
     * What an operation is proposed at when the catalogue gives no quantity.
     * A proposal, never a stored fact: what gets saved is whatever the processor
     * leaves in the field.
     */
    private static final int DEFAULT_UNITS_PER_PRODUCT = 1;

    private static final List<ProductionOrderScopeRequestStatus> LIVE_STATUSES =
            List.of(ProductionOrderScopeRequestStatus.PENDING,
                    ProductionOrderScopeRequestStatus.IN_REVIEW,
                    ProductionOrderScopeRequestStatus.COMPLETED);

    // ── Raising ──────────────────────────────────────────────────────────────

    @Transactional
    public ProductionOrderScopeRequestResponse create(
            ProductionOrderScopeRequestCreateRequest request, Long requesterId
    ) {
        User requester = loadUser(requesterId);
        ProductionOrder order = productionOrderRepository.findById(request.getProductionOrderId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Proizvodni nalog nije pronađen: " + request.getProductionOrderId()));

        List<ProductionOrderLineItem> orderLines =
                lineItemRepository.findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(
                        order.getId());
        if (orderLines.isEmpty()) {
            throw new ConflictException("Nalog nema nijednu aktivnu stavku, pa nema šta da se razradi.");
        }

        List<ProductionOrderScopeRequestCreateRequest.Item> requestedItems =
                resolveRequestedItems(request, orderLines);

        Map<Long, ProductionOrderLineItem> linesById = orderLines.stream()
                .collect(Collectors.toMap(ProductionOrderLineItem::getId, line -> line));

        // Refused as a whole rather than line by line: a request that silently
        // covered three of the four lines somebody picked is not the request they
        // made.
        List<Long> covered = requestRepository.findCoveredLineItemIds(
                requestedItems.stream()
                        .map(ProductionOrderScopeRequestCreateRequest.Item::getProductionOrderLineItemId)
                        .toList(),
                LIVE_STATUSES);
        if (!covered.isEmpty()) {
            throw new ConflictException(
                    "Za neke stavke već postoji zahtev za razradu (otvoren ili završen). "
                            + "Postojeći zahtev prvo treba završiti ili povući.");
        }

        ProductionOrderScopeRequest saved = ProductionOrderScopeRequest.builder()
                .productionOrder(order)
                .scope(request.getScope())
                .createdBy(requester)
                .status(ProductionOrderScopeRequestStatus.PENDING)
                .build();

        for (ProductionOrderScopeRequestCreateRequest.Item item : requestedItems) {
            ProductionOrderLineItem line = linesById.get(item.getProductionOrderLineItemId());
            if (line == null) {
                throw new IllegalArgumentException(
                        "Stavka ne pripada ovom nalogu ili više nije aktivna: "
                                + item.getProductionOrderLineItemId());
            }
            saved.addItem(ProductionOrderScopeRequestItem.builder()
                    .lineItem(line)
                    .note(normalize(item.getNote()))
                    .lineOrder(line.getLineOrder() == null ? 1 : line.getLineOrder())
                    .build());
        }

        ProductionOrderScopeRequest persisted = requestRepository.save(saved);

        outboxEventPublisher.publish(
                OutboxEventType.ORDER_SCOPE_REQUEST_CREATED,
                OutboxAggregateType.ORDER_SCOPE_REQUEST,
                persisted.getId(),
                payloadFor(persisted)
        );

        return toResponse(persisted, persisted.getItems());
    }

    /**
     * What the request actually covers.
     *
     * <p>A LINE_ITEM request names exactly one line — more than one would be an
     * order-wide request wearing the wrong label, and the two are told apart
     * nowhere else. An ORDER request that names none covers every active line,
     * each with its own note prefilled from the line; that is the convenience
     * path, and the client normally sends the edited notes instead.
     */
    private List<ProductionOrderScopeRequestCreateRequest.Item> resolveRequestedItems(
            ProductionOrderScopeRequestCreateRequest request,
            List<ProductionOrderLineItem> orderLines
    ) {
        List<ProductionOrderScopeRequestCreateRequest.Item> items =
                request.getItems() == null ? List.of() : request.getItems();

        if (request.getScope() == ProductionOrderScopeRequestScope.LINE_ITEM) {
            if (items.size() != 1) {
                throw new IllegalArgumentException(
                        "Zahtev za jednu stavku mora da navede tačno jednu stavku naloga.");
            }
            return items;
        }

        if (!items.isEmpty()) {
            long distinct = items.stream()
                    .map(ProductionOrderScopeRequestCreateRequest.Item::getProductionOrderLineItemId)
                    .distinct().count();
            if (distinct != items.size()) {
                throw new IllegalArgumentException("Ista stavka naloga je navedena više puta.");
            }
            return items;
        }

        Map<Long, String> defaultNotes = defaultNotes(orderLines);
        return orderLines.stream().map(line -> {
            ProductionOrderScopeRequestCreateRequest.Item item =
                    new ProductionOrderScopeRequestCreateRequest.Item();
            item.setProductionOrderLineItemId(line.getId());
            item.setNote(defaultNotes.get(line.getId()));
            return item;
        }).toList();
    }

    /**
     * The note each line already carries, which is what a request about it starts
     * from.
     *
     * <p>A line's notes are a list, so they are joined: leaving all but one out
     * would hand the processor a partial version of what the order says.
     */
    private Map<Long, String> defaultNotes(List<ProductionOrderLineItem> lines) {
        List<Long> ids = lines.stream().map(ProductionOrderLineItem::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> byLine = new HashMap<>();
        for (ProductionOrderLineItemNote note : lineItemNoteRepository.findActiveByLineItemIds(ids)) {
            if (note.getNote() == null || note.getNote().isBlank()) {
                continue;
            }
            byLine.merge(note.getProductionOrderLineItem().getId(), note.getNote().trim(),
                    (held, next) -> held + "\n" + next);
        }

        // The line's own single note column, for lines written before the notes
        // table was used. Only when nothing better was found.
        for (ProductionOrderLineItem line : lines) {
            if (!byLine.containsKey(line.getId())
                    && line.getNote() != null && !line.getNote().isBlank()) {
                byLine.put(line.getId(), line.getNote().trim());
            }
        }
        return byLine;
    }

    /**
     * The notes an order's lines would start from, so the dialog that raises a
     * request can offer them for editing before anything is written.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrderScopeLineResponse> proposedLines(Long productionOrderId) {
        List<ProductionOrderLineItem> lines =
                lineItemRepository.findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(
                        productionOrderId);
        Map<Long, String> notes = defaultNotes(lines);
        Set<Long> covered = new HashSet<>(requestRepository.findCoveredLineItemIds(
                lines.stream().map(ProductionOrderLineItem::getId).toList(), LIVE_STATUSES));

        return lines.stream()
                // A line already covered is not on offer: raising a second request
                // for it would be refused on submit anyway, and offering it is how
                // people find that out the slow way.
                .filter(line -> !covered.contains(line.getId()))
                .map(line -> new ProductionOrderScopeLineResponse(
                        null,
                        line.getId(),
                        line.getProduct().getId(),
                        line.getProduct().getProductName(),
                        line.getProductDescription(),
                        line.getQuantity(),
                        notes.get(line.getId())))
                .toList();
    }

    // ── Owning ───────────────────────────────────────────────────────────────

    @Transactional
    public ProductionOrderScopeRequestResponse assign(
            Long requestId, Long actorId, Long assigneeUserId
    ) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);
        User assignee = loadUser(assigneeUserId == null ? actorId : assigneeUserId);

        if (request.getStatus() == ProductionOrderScopeRequestStatus.IN_REVIEW) {
            request.reassignTo(assignee);
        } else {
            request.assignTo(assignee);
        }

        outboxEventPublisher.publish(
                OutboxEventType.ORDER_SCOPE_REQUEST_ASSIGNED,
                OutboxAggregateType.ORDER_SCOPE_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request, request.getItems());
    }

    @Transactional
    public ProductionOrderScopeRequestResponse release(Long requestId, Long actorId) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);
        requireAssignee(request, actorId);
        request.release();
        return toResponse(request, request.getItems());
    }

    // ── Answering ────────────────────────────────────────────────────────────

    /**
     * Saves the decided scope WITHOUT handing it over.
     *
     * <p>The request stays IN_REVIEW and keeps its owner, so a scope that takes
     * two sittings does not have to be finished in one — and a half-finished one
     * is never readable as the order's agreed answer.
     */
    @Transactional
    public ProductionOrderScopeRequestDetailResponse saveDraft(
            Long requestId, Long processorId, ProductionOrderScopeResultRequest payload
    ) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);

        requireNotOwnRequest(request, processorId);
        claimIfUnowned(request, loadUser(processorId));
        requireAssignee(request, processorId);

        writeResult(request, payload);
        request.saveDraft();

        return detailOf(request, processorId);
    }

    /**
     * Saves the decided scope AND hands it over, completing the request.
     *
     * <p>One transaction, so a failure while writing the operations can never
     * leave a COMPLETED request with a half-written answer.
     */
    @Transactional
    public ProductionOrderScopeRequestDetailResponse submit(
            Long requestId, Long processorId, ProductionOrderScopeResultRequest payload
    ) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);
        User processor = loadUser(processorId);

        requireNotOwnRequest(request, processorId);
        claimIfUnowned(request, processor);
        requireAssignee(request, processorId);

        writeResult(request, payload);
        request.submit(processor, payload.getDecisionNote());

        outboxEventPublisher.publish(
                OutboxEventType.ORDER_SCOPE_REQUEST_COMPLETED,
                OutboxAggregateType.ORDER_SCOPE_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return detailOf(request, processorId);
    }

    /**
     * Replaces the answer on every covered line.
     *
     * <p>Every covered line must be present: a payload that leaves one out would
     * produce an order whose scope is decided in part, and "how much is done"
     * would then be measured against a denominator with a hole in it.
     */
    private void writeResult(
            ProductionOrderScopeRequest request, ProductionOrderScopeResultRequest payload
    ) {
        // Asked BEFORE anything is written: discovering the refusal afterwards
        // would mean rolling back writes that should never have been attempted.
        request.requireEditableResult();

        Map<Long, ProductionOrderScopeRequestItem> itemsById = request.getItems().stream()
                .collect(Collectors.toMap(ProductionOrderScopeRequestItem::getId, item -> item));

        Set<Long> answered = payload.getItems().stream()
                .map(ProductionOrderScopeResultRequest.Item::getItemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (answered.size() != payload.getItems().size()) {
            throw new IllegalArgumentException("Ista stavka zahteva je poslata više puta.");
        }
        if (!answered.equals(itemsById.keySet())) {
            throw new IllegalArgumentException(
                    "Razrada mora da obuhvati tačno one stavke koje zahtev pokriva.");
        }

        for (ProductionOrderScopeResultRequest.Item answer : payload.getItems()) {
            ProductionOrderScopeRequestItem item = itemsById.get(answer.getItemId());
            item.replaceOperations(operationsFor(item, answer));
        }
    }

    /**
     * The rows one line's answer becomes.
     *
     * <p>Every operation named must belong to that line's product — an operation
     * from a different product would put work into an order's scope that the
     * order cannot contain.
     */
    private List<ProductionOrderScopeRequestOperation> operationsFor(
            ProductionOrderScopeRequestItem item, ProductionOrderScopeResultRequest.Item answer
    ) {
        Long productId = item.getLineItem().getProduct().getId();
        Map<Long, Operation> catalogue = catalogueOperations(List.of(productId)).stream()
                .collect(Collectors.toMap(Operation::getId, operation -> operation));

        List<ProductionOrderScopeRequestOperation> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int order = 1;

        for (ProductionOrderScopeResultRequest.Operation decided : answer.getOperations()) {
            Operation operation = catalogue.get(decided.getOperationId());
            if (operation == null) {
                throw new IllegalArgumentException(
                        "Operacija ne pripada proizvodu sa ove stavke: " + decided.getOperationId());
            }
            if (!seen.add(operation.getId())) {
                throw new IllegalArgumentException(
                        "Operacija je navedena više puta: " + operation.getOpName());
            }
            if (decided.isNeeded() && decided.getUnitsPerProduct() == null) {
                throw new IllegalArgumentException(
                        "Za operaciju \"" + operation.getOpName()
                                + "\" upišite količinu u sklopu ili je označite kao nepotrebnu.");
            }

            rows.add(ProductionOrderScopeRequestOperation.builder()
                    .operation(operation)
                    .operationName(operation.getOpName())
                    .needed(decided.isNeeded())
                    .unitsPerProductSnapshot(operation.getUnitsPerProduct())
                    // A quantity on an operation marked not needed is dropped
                    // rather than stored: the database refuses to hold both, and
                    // "not needed, three per assembly" is not a fact.
                    .unitsPerProductValue(decided.isNeeded() ? decided.getUnitsPerProduct() : null)
                    .lineOrder(order++)
                    .build());
        }

        return rows;
    }

    // ── Refusing and withdrawing ─────────────────────────────────────────────

    @Transactional
    public ProductionOrderScopeRequestResponse decline(Long requestId, Long processorId, String note) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);
        User processor = loadUser(processorId);

        requireNotOwnRequest(request, processorId);
        requireAssignee(request, processorId);

        request.decline(processor, note);

        outboxEventPublisher.publish(
                OutboxEventType.ORDER_SCOPE_REQUEST_DECLINED,
                OutboxAggregateType.ORDER_SCOPE_REQUEST,
                request.getId(),
                payloadFor(request)
        );

        return toResponse(request, request.getItems());
    }

    /** The requester withdraws their own still-open request; a processor may too. */
    @Transactional
    public ProductionOrderScopeRequestResponse cancel(Long requestId, Long actorId, String note) {
        ProductionOrderScopeRequest request = loadForUpdate(requestId);

        boolean isRequester = request.getCreatedBy().getId().equals(actorId);
        boolean mayProcess =
                permissionService.hasPermission(AppPermission.ORDER_SCOPE_REQUEST_PROCESS);

        if (!isRequester && !mayProcess) {
            throw new AccessDeniedException("Nije dozvoljeno otkazivanje tuđeg zahteva.");
        }

        request.cancel(note);
        return toResponse(request, request.getItems());
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ProductionOrderScopeRequestResponse> search(
            ProductionOrderScopeRequestStatus status,
            Long productionOrderId,
            Long createdById,
            Long assignedToId,
            Long mineUserId,
            java.time.OffsetDateTime createdFrom,
            java.time.OffsetDateTime createdTo,
            Pageable pageable
    ) {
        Page<ProductionOrderScopeRequest> page = requestRepository.search(
                status, productionOrderId, createdById, assignedToId, mineUserId,
                ProductionOrderScopeRequestStatus.PENDING,
                ProductionOrderScopeRequestStatus.IN_REVIEW,
                ProductionOrderScopeRequestStatus.COMPLETED,
                ProductionOrderScopeRequestStatus.DECLINED,
                createdFrom, createdTo,
                pageable);

        Map<Long, List<ProductionOrderScopeRequestItem>> itemsByRequest =
                itemsFor(page.getContent());

        return page.map(request -> toResponse(
                request, itemsByRequest.getOrDefault(request.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public List<ProductionOrderScopeRequestResponse> forProductionOrder(
            Long productionOrderId, Long restrictToCreatedById
    ) {
        List<ProductionOrderScopeRequest> requests =
                requestRepository.findByProductionOrderAndStatusIn(
                        productionOrderId, LIVE_STATUSES, restrictToCreatedById);

        Map<Long, List<ProductionOrderScopeRequestItem>> itemsByRequest = itemsFor(requests);

        return requests.stream()
                .map(request -> toResponse(
                        request, itemsByRequest.getOrDefault(request.getId(), List.of())))
                .toList();
    }

    /**
     * One request with its answer — or, before anything is saved, with the
     * proposal the processor is about to edit.
     */
    @Transactional(readOnly = true)
    public ProductionOrderScopeRequestDetailResponse getDetail(Long requestId, Long callerId) {
        ProductionOrderScopeRequest request = loadDetail(requestId);

        if (!permissionService.hasPermission(AppPermission.ORDER_SCOPE_REQUEST_READ_ALL)
                && !request.getCreatedBy().getId().equals(callerId)) {
            throw new AccessDeniedException("Nemate pristup ovom zahtevu.");
        }

        return detailOf(request, callerId);
    }

    private ProductionOrderScopeRequestDetailResponse detailOf(
            ProductionOrderScopeRequest request, Long callerId
    ) {
        List<ProductionOrderScopeRequestItem> items =
                requestRepository.findItemsWithOperations(request.getId());
        items = new ArrayList<>(items);
        items.sort(Comparator
                .comparing((ProductionOrderScopeRequestItem item) ->
                        item.getLineOrder() == null ? 1 : item.getLineOrder())
                .thenComparing(ProductionOrderScopeRequestItem::getId));

        // The catalogue, read once for every product on the request rather than
        // once per line.
        Map<Long, List<Operation>> catalogueByProduct = catalogueOperations(
                items.stream()
                        .map(item -> item.getLineItem().getProduct().getId())
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.groupingBy(operation -> operation.getProduct().getId()));

        boolean submitted = request.getResultState() == ProductionOrderScopeResultState.SUBMITTED;

        List<ProductionOrderScopeItemResponse> itemResponses = items.stream()
                .map(item -> new ProductionOrderScopeItemResponse(
                        lineOf(item),
                        operationRows(
                                item,
                                catalogueByProduct.getOrDefault(
                                        item.getLineItem().getProduct().getId(), List.of()),
                                submitted)))
                .toList();

        return new ProductionOrderScopeRequestDetailResponse(
                toResponse(request, items), itemResponses, isEditableBy(request, callerId));
    }

    /**
     * What the modal shows for one line.
     *
     * <p>Nothing saved yet: the product's catalogue, everything needed, the
     * catalogue quantity proposed — the starting point the processor edits.
     *
     * <p>A DRAFT: what was saved, plus any operation the catalogue has gained
     * since, so an operation added to the product after the draft was started is
     * not silently missing from the answer.
     *
     * <p>SUBMITTED: exactly what was handed over, and nothing else. This is the
     * record the order's progress is measured against, and it must read the same
     * today as it did the day it was agreed.
     */
    private List<ProductionOrderScopeOperationResponse> operationRows(
            ProductionOrderScopeRequestItem item, List<Operation> catalogue, boolean submitted
    ) {
        List<ProductionOrderScopeOperationResponse> rows = item.getOperations().stream()
                .map(saved -> new ProductionOrderScopeOperationResponse(
                        saved.getOperation().getId(),
                        saved.getOperationName(),
                        Boolean.TRUE.equals(saved.getNeeded()),
                        saved.getUnitsPerProductSnapshot(),
                        saved.getUnitsPerProductValue()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (submitted) {
            return rows;
        }

        Set<Long> alreadyThere = rows.stream()
                .map(ProductionOrderScopeOperationResponse::operationId)
                .collect(Collectors.toSet());

        catalogue.stream()
                .filter(operation -> !alreadyThere.contains(operation.getId()))
                .forEach(operation -> rows.add(new ProductionOrderScopeOperationResponse(
                        operation.getId(),
                        operation.getOpName(),
                        true,
                        // The snapshot stays exactly what the catalogue said,
                        // NULL included: it is the record of what was there, and
                        // a proposal must not be able to rewrite it.
                        operation.getUnitsPerProduct(),
                        // An operation the catalogue never gave a quantity is
                        // proposed as one per assembly. It is the only number
                        // that says "once, unless you know better", and it saves
                        // the processor typing 1 on every such row -- which is
                        // what they would type.
                        operation.getUnitsPerProduct() == null
                                ? DEFAULT_UNITS_PER_PRODUCT
                                : operation.getUnitsPerProduct())));

        return rows;
    }

    /** The live operations of these products, by name — what the modal lists. */
    private List<Operation> catalogueOperations(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return operationRepository.findByProductIdInAndArchivedAtIsNull(productIds).stream()
                .filter(Operation::isActive)
                .sorted(Comparator.comparing(
                        Operation::getOpName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    /**
     * Whether this caller may still write the answer: they hold the permission,
     * they own the request, and it has not been handed over.
     */
    private boolean isEditableBy(ProductionOrderScopeRequest request, Long callerId) {
        return permissionService.hasPermission(AppPermission.ORDER_SCOPE_REQUEST_PROCESS)
                && request.getStatus() == ProductionOrderScopeRequestStatus.IN_REVIEW
                && request.getResultState() != ProductionOrderScopeResultState.SUBMITTED
                && request.getAssignedTo() != null
                && request.getAssignedTo().getId().equals(callerId);
    }

    /**
     * Takes an unowned request on the way to answering it — the same shortcut the
     * manufacturing-time workflow offers, and for the same reason: a processor
     * who opens the modal from the queue has taken the work on, and making them
     * press "claim" first would be ceremony rather than safety.
     */
    private void claimIfUnowned(ProductionOrderScopeRequest request, User processor) {
        if (request.getStatus() == ProductionOrderScopeRequestStatus.PENDING
                && request.getAssignedTo() == null) {
            request.assignTo(processor);
        }
    }

    /** Submitting and deciding are separate responsibilities. */
    private void requireNotOwnRequest(ProductionOrderScopeRequest request, Long actorId) {
        if (request.getCreatedBy().getId().equals(actorId)) {
            throw new AccessDeniedException("Ne možete da obradite sopstveni zahtev.");
        }
    }

    /** Only the current owner decides, so assigned_to always names who did the work. */
    private void requireAssignee(ProductionOrderScopeRequest request, Long actorId) {
        User assignee = request.getAssignedTo();
        if (assignee == null || !assignee.getId().equals(actorId)) {
            throw new AccessDeniedException(
                    "Zahtev mora prvo da bude dodeljen vama da biste ga obradili.");
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private Map<Long, List<ProductionOrderScopeRequestItem>> itemsFor(
            List<ProductionOrderScopeRequest> requests
    ) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        return requestRepository
                .findItemsForRequests(requests.stream()
                        .map(ProductionOrderScopeRequest::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getRequest().getId()));
    }

    private ProductionOrderScopeRequest loadForUpdate(Long requestId) {
        requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Zahtev nije pronađen: " + requestId));
        // Re-read through the fetch-joined query so the response can be built
        // without lazy-loading surprises; the row lock above is already held.
        return loadDetail(requestId);
    }

    private ProductionOrderScopeRequest loadDetail(Long requestId) {
        return requestRepository.findDetailById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Zahtev nije pronađen: " + requestId));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private Map<String, Object> payloadFor(ProductionOrderScopeRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("scope", request.getScope().name());
        payload.put("productionOrderId", request.getProductionOrder().getId());
        payload.put("productionOrderCode", request.getProductionOrder().getCode());
        payload.put("productionOrderName", request.getProductionOrder().getName());
        payload.put("createdByUserId", request.getCreatedBy().getId());
        if (request.getAssignedTo() != null) {
            payload.put("assignedToUserId", request.getAssignedTo().getId());
        }
        return payload;
    }

    private ProductionOrderScopeLineResponse lineOf(ProductionOrderScopeRequestItem item) {
        ProductionOrderLineItem line = item.getLineItem();
        return new ProductionOrderScopeLineResponse(
                item.getId(),
                line.getId(),
                line.getProduct().getId(),
                line.getProduct().getProductName(),
                line.getProductDescription(),
                line.getQuantity(),
                item.getNote());
    }

    private ProductionOrderScopeRequestResponse toResponse(
            ProductionOrderScopeRequest r, List<ProductionOrderScopeRequestItem> items
    ) {
        return new ProductionOrderScopeRequestResponse(
                r.getId(),
                r.getProductionOrder().getId(),
                r.getProductionOrder().getCode(),
                r.getProductionOrder().getName(),
                r.getScope(),
                r.getStatus(),
                r.getResultState(),
                r.getCreatedBy().getId(),
                r.getCreatedBy().getFullName(),
                r.getAssignedTo() == null ? null : r.getAssignedTo().getId(),
                r.getAssignedTo() == null ? null : r.getAssignedTo().getFullName(),
                r.getProcessedBy() == null ? null : r.getProcessedBy().getId(),
                r.getProcessedBy() == null ? null : r.getProcessedBy().getFullName(),
                r.getProcessedAt(),
                r.getDecisionNote(),
                r.getCancelledAt(),
                r.getCreatedAt(),
                items.stream().map(this::lineOf).toList());
    }
}
