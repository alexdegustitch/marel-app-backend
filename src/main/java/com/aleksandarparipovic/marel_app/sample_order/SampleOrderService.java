package com.aleksandarparipovic.marel_app.sample_order;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.customer.Customer;
import com.aleksandarparipovic.marel_app.customer.CustomerRepository;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCardRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCopySourceLineItemRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCopySourceRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCreateRequest;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderDetailDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemNoteDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemQuantityDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderOptionDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.sample_order.specification.SampleOrderSpecifications;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import com.aleksandarparipovic.marel_app.sample_order_line_item.repository.SampleOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.sample_order_line_item_note.SampleOrderLineItemNote;
import com.aleksandarparipovic.marel_app.sample_order_line_item_note.repository.SampleOrderLineItemNoteRepository;
import com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.SampleOrderLineItemQuantity;
import com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.repository.SampleOrderLineItemQuantityRepository;
import com.aleksandarparipovic.marel_app.sample_order_recipient.SampleOrderRecipientService;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Nalozi za izradu uzoraka.
 *
 * <p>Written against the production-order service, and shorter than it by
 * exactly the things a sample order does not have. There are no priority flags,
 * no list of delivery windows, and one quantity per line rather than a dated
 * series — a sample run goes out once, so anything that describes a second
 * delivery would be describing something that never happens.
 *
 * <p>What IS the same is deliberate: the same soft-delete-and-reinsert on every
 * save so the history stays readable, the same recipient snapshot, the same
 * one-conversation-per-order mail, and the same copy-from-a-past-order picker.
 * Somebody who writes both kinds of order should not have to learn two sets of
 * behaviour.
 */
@Service
@RequiredArgsConstructor
public class SampleOrderService {

    /** Serbian date order, the one used everywhere else people read dates here. */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private static final int MAX_PAGE_SIZE = 200;

    private final SampleOrderRepository sampleOrderRepository;
    private final SampleOrderLineItemRepository lineItemRepository;
    private final SampleOrderLineItemQuantityRepository quantityRepository;
    private final SampleOrderLineItemNoteRepository noteRepository;
    private final SampleOrderMapper mapper;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final SampleOrderRecipientService recipientService;
    private final PermissionService permissionService;
    private final OutboxEventPublisher outboxEventPublisher;

    // ── Writing ─────────────────────────────────────────────────────────────

    @Transactional
    public SampleOrderDetailDto create(SampleOrderCreateRequest req) {
        Long userId = currentUserService.getCurrentUserId();
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;

        LocalDate creationDate = req.creationDate() != null ? req.creationDate() : LocalDate.now();
        requireDeadlineNotBeforeCreation(creationDate, req.deadlineDate());

        SampleOrder order = new SampleOrder();
        order.setCode(req.code().trim());
        order.setName(req.name().trim());
        order.setNote(blankToNull(req.note()));
        order.setCreationDate(creationDate);
        order.setDeadlineDate(req.deadlineDate());
        order.setDeadlineNote(blankToNull(req.deadlineNote()));
        order.setStatus(SampleOrderStatus.CREATED);
        order.setIsActive(true);
        order.setUser(user);
        order.setCustomer(resolveCustomer(req.customerId()));
        order = sampleOrderRepository.save(order);

        // AFTER the save, because a snapshot needs the order's id — but still
        // inside this transaction, so an order never survives with a list
        // attached and no recipients copied, nor the other way round.
        attachRequestedMailingLists(order.getId(), req.mailingListIds(), userId);

        List<SampleOrderLineItemDto> lineItemDtos = new ArrayList<>();
        if (req.lineItems() != null) {
            int position = 1;
            for (var itemReq : req.lineItems()) {
                lineItemDtos.add(writeLineItem(
                        order,
                        itemReq.productId(),
                        itemReq.description(),
                        itemReq.note(),
                        itemReq.lineOrder() != null ? itemReq.lineOrder() : position,
                        itemReq.quantity()));
                position++;
            }
        }

        // LAST, and still inside this transaction. Last because the mail this
        // event sends opens the order's conversation and should describe an order
        // that is fully written, not one still being assembled. Inside, because
        // OutboxEventPublisher is MANDATORY: an announcement must not survive a
        // rollback of the thing it announces.
        publishOrderCreated(order);

        return mapper.toDetailDto(order, lineItemDtos);
    }

    /**
     * Full edit of an existing order — everything except the code (immutable once
     * created) and the status (changed only via {@link #close}).
     *
     * <p>Line items, with their quantity and note revisions, are never overwritten
     * in place: the currently active ones are deactivated and a fresh set is
     * inserted from the submitted form, same as create(). This preserves the old
     * rows for audit instead of mutating them, matching the isActive soft-delete
     * convention already used throughout this table group.
     */
    @Transactional
    public SampleOrderDetailDto update(Long id, SampleOrderUpdateRequest req) {
        SampleOrder order = loadOrder(id);
        requireOpen(order);

        // BEFORE the first setter. The scalars below are mutated on the managed
        // entity in place, so a snapshot taken any later would already be the new
        // values compared against themselves — every save would report no change.
        OrderSnapshot before = snapshot(order);

        LocalDate creationDate = req.creationDate() != null ? req.creationDate() : order.getCreationDate();
        requireDeadlineNotBeforeCreation(creationDate, req.deadlineDate());

        order.setName(req.name().trim());
        order.setNote(blankToNull(req.note()));
        order.setCreationDate(creationDate);
        order.setDeadlineDate(req.deadlineDate());
        order.setDeadlineNote(blankToNull(req.deadlineNote()));
        // Null clears it, as with every other field on this form: the client
        // sends the whole order back on every save.
        order.setCustomer(resolveCustomer(req.customerId()));
        order = sampleOrderRepository.save(order);

        deactivateLineItems(id);

        List<SampleOrderLineItemDto> lineItemDtos = new ArrayList<>();
        if (req.lineItems() != null) {
            int position = 1;
            for (var itemReq : req.lineItems()) {
                lineItemDtos.add(writeLineItem(
                        order,
                        itemReq.productId(),
                        itemReq.description(),
                        itemReq.note(),
                        itemReq.lineOrder() != null ? itemReq.lineOrder() : position,
                        itemReq.quantity()));
                position++;
            }
        }

        publishOrderUpdated(order, before);

        return mapper.toDetailDto(order, lineItemDtos);
    }

    /**
     * Closes the order.
     *
     * <p>One-way, like a production order's "delivered": there are two states
     * and the second is terminal. Records WHO closed it — an order that is
     * finished and cannot say by whom is a gap in exactly the record this
     * feature exists to keep.
     */
    @Transactional
    public SampleOrderDetailDto close(Long id) {
        SampleOrder order = loadOrder(id);

        boolean alreadyClosed = SampleOrderStatus.isClosed(order.getStatus());

        Long actorId = currentUserService.getCurrentUserId();
        order.setStatus(SampleOrderStatus.CLOSED);
        if (!alreadyClosed && actorId != null) {
            order.setClosedBy(userRepository.findById(actorId).orElse(null));
        }
        order = sampleOrderRepository.save(order);

        // Published only on the actual transition. Outbox idempotency is keyed on
        // the outbox row, so re-closing a closed order would otherwise create a
        // second event and notify everybody again.
        if (!alreadyClosed) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderCode", order.getCode());
            payload.put("orderName", order.getName());
            payload.put("responsibleUserId", order.getUser() == null ? null : order.getUser().getId());
            payload.put("actorUserId", actorId);

            outboxEventPublisher.publish(
                    OutboxEventType.SAMPLE_ORDER_COMPLETED,
                    OutboxAggregateType.SAMPLE_ORDER,
                    order.getId(),
                    payload
            );
        }

        return getDetail(order.getId());
    }

    /**
     * One line, plus the first revision of its quantity and — when it has one —
     * of its note.
     *
     * <p>The quantity is written BOTH on the line and as a row in
     * sample_order_line_item_quantities. The line carries the number every query
     * reads; the revision row is what makes "this used to be 40" answerable after
     * somebody changes it, which is the whole reason that child table exists.
     */
    private SampleOrderLineItemDto writeLineItem(
            SampleOrder order,
            Long productId,
            String description,
            String note,
            int lineOrder,
            Integer quantity
    ) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Proizvod nije pronađen (id=" + productId + ")"));

        SampleOrderLineItem lineItem = new SampleOrderLineItem();
        lineItem.setSampleOrder(order);
        lineItem.setProduct(product);
        lineItem.setOrderLine(lineOrder);
        lineItem.setQuantity(quantity);
        lineItem.setProductDescription(blankToNull(description));
        lineItem.setNote(blankToNull(note));
        lineItem.setIsActive(true);
        lineItem = lineItemRepository.save(lineItem);

        SampleOrderLineItemQuantity quantityRow = new SampleOrderLineItemQuantity();
        quantityRow.setSampleOrderLineItem(lineItem);
        quantityRow.setOrderQuantity(1);
        quantityRow.setQuantity(quantity);
        quantityRow.setIsActive(true);
        SampleOrderLineItemQuantityDto quantityDto =
                mapper.toLineItemQuantityDto(quantityRepository.save(quantityRow));

        List<SampleOrderLineItemNoteDto> noteDtos = new ArrayList<>();
        String cleanNote = blankToNull(note);
        if (cleanNote != null) {
            SampleOrderLineItemNote noteRow = new SampleOrderLineItemNote();
            noteRow.setSampleOrderLineItem(lineItem);
            noteRow.setOrderQuantity(1);
            noteRow.setNote(cleanNote);
            noteRow.setIsActive(true);
            noteDtos.add(mapper.toLineItemNoteDto(noteRepository.save(noteRow)));
        }

        return mapper.toLineItemDto(lineItem, List.of(quantityDto), noteDtos);
    }

    /**
     * Retires the order's live lines and everything hanging off them.
     *
     * <p>The children go first. uq_sample_order_line_items_order_line_active
     * covers live lines only, so the line has to stop being live before the
     * replacement with the same position can be inserted — but a quantity row
     * left active under an archived line would be a revision of nothing.
     */
    private void deactivateLineItems(Long orderId) {
        for (SampleOrderLineItem existing
                : lineItemRepository.findBySampleOrder_IdAndIsActiveIsTrueOrderByOrderLineAsc(orderId)) {

            for (SampleOrderLineItemQuantity quantity
                    : quantityRepository.findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(existing.getId())) {
                if (Boolean.TRUE.equals(quantity.getIsActive())) {
                    quantity.setIsActive(false);
                    quantityRepository.save(quantity);
                }
            }
            for (SampleOrderLineItemNote note
                    : noteRepository.findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(existing.getId())) {
                if (Boolean.TRUE.equals(note.getIsActive())) {
                    note.setIsActive(false);
                    noteRepository.save(note);
                }
            }

            existing.setIsActive(false);
            lineItemRepository.save(existing);
        }
        // Flushed by the next query in this transaction; the replacements are
        // inserted afterwards, so the partial unique index never sees two live
        // lines on one position.
        lineItemRepository.flush();
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    List<SampleOrderOptionDto> getAllActiveSampleOrders() {
        return sampleOrderRepository.findByIsActiveIsTrueOrderByNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SampleOrderCardRow> searchAll(SearchRequest request) {
        Specification<SampleOrder> specification = SampleOrderSpecifications.fromSearchRequest(request);
        Pageable pageable = stableSort(PageableBuilder.from(request));

        Page<SampleOrder> page = sampleOrderRepository.findAll(specification, pageable);
        List<SampleOrderCardRow> rows = buildCardRows(page.getContent());

        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /**
     * The sort a list can page through twice and get the same orders.
     *
     * <p>Two things the request cannot say for itself.
     *
     * <p><b>NULLS LAST.</b> A date nobody filled in is not the newest thing that
     * happened. PostgreSQL puts nulls first on a DESC sort, so "creation date,
     * newest first" would otherwise open on every order whose date is blank.
     *
     * <p><b>The id last.</b> Plenty of orders share a date, and rows tied on the
     * whole sort key have no order the database is obliged to keep. It is free to
     * return them one way for page 1 and another for page 2, which shows one
     * order twice and hides another entirely. Nobody reports that as a bug; they
     * report that an order "disappeared".
     */
    private static Pageable stableSort(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> orders = new ArrayList<>();
        boolean tieBroken = false;
        for (Sort.Order order : sort) {
            orders.add(order.nullsLast());
            tieBroken |= "id".equals(order.getProperty());
        }
        if (!tieBroken) {
            orders.add(new Sort.Order(Sort.Direction.DESC, "id"));
        }

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    /**
     * The cards for one page, with how much each order is for.
     *
     * <p>One extra query for the whole page rather than one per card. The count
     * and the total are what the card actually shows, and a lazy read of the
     * lines would make a twenty-card list twenty round trips.
     */
    private List<SampleOrderCardRow> buildCardRows(List<SampleOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream().map(SampleOrder::getId).toList();
        Map<Long, List<SampleOrderLineItem>> byOrder = lineItemRepository
                .findActiveWithProductByOrderIds(orderIds)
                .stream()
                .collect(Collectors.groupingBy(li -> li.getSampleOrder().getId()));

        return orders.stream()
                .map(order -> {
                    List<SampleOrderLineItem> lines = byOrder.getOrDefault(order.getId(), List.of());
                    int total = lines.stream()
                            .mapToInt(li -> li.getQuantity() == null ? 0 : li.getQuantity())
                            .sum();
                    return mapper.toCardRow(order, lines.size(), total);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SampleOrderDetailDto getDetail(Long id) {
        SampleOrder order = loadOrder(id);

        List<SampleOrderLineItem> activeLineItems =
                lineItemRepository.findBySampleOrder_IdAndIsActiveIsTrueOrderByOrderLineAsc(id);

        List<SampleOrderLineItemDto> lineItems = activeLineItems.stream()
                .map(lineItem -> {
                    List<SampleOrderLineItemQuantityDto> quantities = quantityRepository
                            .findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(lineItem.getId())
                            .stream()
                            .map(mapper::toLineItemQuantityDto)
                            .toList();
                    List<SampleOrderLineItemNoteDto> notes = noteRepository
                            .findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(lineItem.getId())
                            .stream()
                            .map(mapper::toLineItemNoteDto)
                            .toList();
                    return mapper.toLineItemDto(lineItem, quantities, notes);
                })
                .toList();

        return mapper.toDetailDto(order, lineItems);
    }

    // ── Copying from a past order ───────────────────────────────────────────

    /**
     * Past sample orders to copy line items OUT of.
     *
     * <p>The picker behind "Kopiraj stavku". Somebody writing an order remembers
     * having made nearly this one before, and re-typing a line — the product, the
     * description the shop floor works from, the quantity — is both slow and how
     * the description quietly drifts from the one that worked.
     *
     * <p><b>Searched, filtered, sorted and paged HERE.</b> One box over
     * everything an order is recognised by, plus three narrowings that answer
     * "which one was it" when the box cannot: who wrote it, who it was for, and
     * when it was created. The browser holds one page and narrows nothing.
     *
     * <p>Every LIVE line of every matching order comes back, not only the lines
     * that matched. An order found by its customer's name has ten lines and the
     * reader may want any of them. Which lines DID match is marked, so they can
     * be found among the rest.
     */
    @Transactional(readOnly = true)
    public Page<SampleOrderCopySourceRow> searchCopySources(
            String search,
            Long customerId,
            Long userId,
            LocalDate createdFrom,
            LocalDate createdTo,
            int page,
            int size
    ) {
        String text = (search == null || search.isBlank()) ? null : search.trim();

        Specification<SampleOrder> specification = SampleOrderSpecifications.notArchived();
        if (text != null) {
            specification = specification.and(SampleOrderSpecifications.matchesAnything(text));
        }
        if (customerId != null) {
            specification = specification.and(SampleOrderSpecifications.customerIs(customerId));
        }
        if (userId != null) {
            specification = specification.and(SampleOrderSpecifications.writtenBy(userId));
        }
        if (createdFrom != null || createdTo != null) {
            specification = specification.and(
                    SampleOrderSpecifications.createdBetween(createdFrom, createdTo));
        }

        Pageable pageable = stableSort(PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "creationDate")));

        Page<SampleOrder> orders = sampleOrderRepository.findAll(specification, pageable);
        return new PageImpl<>(buildCopySourceRows(orders.getContent(), text), pageable,
                orders.getTotalElements());
    }

    /**
     * The rows for one page of copy sources, lines and notes included.
     *
     * <p>Two queries for the whole page rather than two per order.
     */
    private List<SampleOrderCopySourceRow> buildCopySourceRows(List<SampleOrder> orders, String search) {
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream().map(SampleOrder::getId).toList();
        Map<Long, List<SampleOrderLineItem>> lineItemsByOrder = lineItemRepository
                .findActiveWithProductByOrderIds(orderIds)
                .stream()
                .collect(Collectors.groupingBy(
                        lineItem -> lineItem.getSampleOrder().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<Long> lineItemIds = lineItemsByOrder.values().stream()
                .flatMap(List::stream)
                .map(SampleOrderLineItem::getId)
                .toList();

        Map<Long, List<SampleOrderLineItemNote>> notesByLineItem = lineItemIds.isEmpty()
                ? Map.of()
                : noteRepository.findActiveByLineItemIds(lineItemIds)
                        .stream()
                        .collect(Collectors.groupingBy(
                                note -> note.getSampleOrderLineItem().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()));

        return orders.stream()
                .map(order -> toCopySourceRow(order, lineItemsByOrder, notesByLineItem, search))
                .toList();
    }

    private SampleOrderCopySourceRow toCopySourceRow(
            SampleOrder order,
            Map<Long, List<SampleOrderLineItem>> lineItemsByOrder,
            Map<Long, List<SampleOrderLineItemNote>> notesByLineItem,
            String search
    ) {
        List<SampleOrderCopySourceLineItemRow> lineItems = lineItemsByOrder
                .getOrDefault(order.getId(), List.of())
                .stream()
                .map(lineItem -> toCopySourceLineItemRow(
                        lineItem, notesByLineItem.getOrDefault(lineItem.getId(), List.of()), search))
                .toList();

        Customer customer = order.getCustomer();
        User user = order.getUser();

        boolean matched = SampleOrderSpecifications.containsText(order.getCode(), search)
                || SampleOrderSpecifications.containsText(order.getName(), search)
                || SampleOrderSpecifications.containsText(order.getNote(), search)
                || (customer != null && (
                        SampleOrderSpecifications.containsText(customer.getName(), search)
                        || SampleOrderSpecifications.containsText(customer.getCode(), search)
                        || SampleOrderSpecifications.containsText(customer.getTaxId(), search)));

        return new SampleOrderCopySourceRow(
                order.getId(),
                order.getCode(),
                order.getName(),
                order.getNote(),
                order.getStatus(),
                order.getCreationDate(),
                order.getDeadlineDate(),
                customer != null ? customer.getId() : null,
                customer != null ? customer.getName() : null,
                customer != null ? customer.getCode() : null,
                customer != null ? customer.getTaxId() : null,
                user != null ? user.getId() : null,
                user != null ? user.getFullName() : null,
                matched,
                lineItems
        );
    }

    private SampleOrderCopySourceLineItemRow toCopySourceLineItemRow(
            SampleOrderLineItem lineItem,
            List<SampleOrderLineItemNote> lineItemNotes,
            String search
    ) {
        Product product = lineItem.getProduct();
        List<String> notes = lineItemNotes.stream()
                .map(SampleOrderLineItemNote::getNote)
                .toList();

        // The quantity is matched as a NUMBER, exactly as the filter matches it —
        // see SampleOrderSpecifications.matchesAnything for why.
        Integer number = SampleOrderSpecifications.parseSearchNumber(search);
        boolean matched = (number != null && number.equals(lineItem.getQuantity()))
                || SampleOrderSpecifications.containsText(lineItem.getNote(), search)
                || SampleOrderSpecifications.containsText(lineItem.getProductDescription(), search)
                || SampleOrderSpecifications.containsText(product.getProductName(), search)
                || SampleOrderSpecifications.containsText(product.getProductCode(), search)
                || notes.stream().anyMatch(note -> SampleOrderSpecifications.containsText(note, search));

        return new SampleOrderCopySourceLineItemRow(
                lineItem.getId(),
                lineItem.getOrderLine(),
                product.getId(),
                product.getProductName(),
                product.getProductCode(),
                lineItem.getProductDescription(),
                lineItem.getNote(),
                lineItem.getQuantity(),
                notes,
                matched
        );
    }

    // ── Announcing ──────────────────────────────────────────────────────────

    /**
     * Opens the order's e-mail conversation.
     *
     * <p>Every later notification about this order is sent as a reply to the mail
     * this event produces, which is why it goes out even though nothing has
     * happened yet: without a first message there is nothing for the rest to hang
     * off, and each change would arrive as an unrelated mail.
     *
     * <p>Recipients come from the snapshot that {@code attachRequestedMailingLists}
     * has already written. An order created without a mailing list has none, and
     * then no mail is queued and no conversation is opened — the first change that
     * does have recipients opens it instead.
     */
    private void publishOrderCreated(SampleOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                OutboxEventType.SAMPLE_ORDER_CREATED,
                OutboxAggregateType.SAMPLE_ORDER,
                order.getId(),
                payload
        );
    }

    /**
     * Everything about an order that a recipient would want to be told changed.
     *
     * <p>Line items are held as an ordered list of VALUE descriptions rather than
     * rows, because update() never edits them in place — it deactivates the old
     * ones and inserts a fresh set on every save. By identity every save looks
     * like a change; by value, only a real one does.
     */
    record OrderSnapshot(
            String name,
            String note,
            String customer,
            LocalDate creationDate,
            LocalDate deadlineDate,
            String deadlineNote,
            List<String> lineItems
    ) {
    }

    private OrderSnapshot snapshot(SampleOrder order) {
        return new OrderSnapshot(
                order.getName(),
                order.getNote(),
                order.getCustomer() == null ? null : order.getCustomer().getName(),
                order.getCreationDate(),
                order.getDeadlineDate(),
                order.getDeadlineNote(),
                describeLineItems(order.getId()));
    }

    /**
     * Line items as one description per line.
     *
     * <p>Ordered by position so that reordering the same products reads as a
     * change — because it is one: the order of lines is what the shop floor works
     * through.
     */
    private List<String> describeLineItems(Long orderId) {
        return lineItemRepository
                .findBySampleOrder_IdAndIsActiveIsTrueOrderByOrderLineAsc(orderId)
                .stream()
                .map(item -> {
                    String product = item.getProduct() == null ? "-" : item.getProduct().getProductName();
                    return product + " (" + item.getQuantity() + " kom)";
                })
                .toList();
    }

    /**
     * Announces what this save actually changed — one message listing every
     * difference, not one message per field.
     *
     * <p>Nothing is published when nothing changed. update() rewrites the line
     * items on every save whether or not the form touched them, so without the
     * comparison every corrected typo would mail the whole recipient list.
     */
    private void publishOrderUpdated(SampleOrder order, OrderSnapshot before) {
        List<String> changes = describeChanges(before, snapshot(order));
        if (changes.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("changes", changes);
        payload.put("responsibleUserId", order.getUser() == null ? null : order.getUser().getId());
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                OutboxEventType.SAMPLE_ORDER_UPDATED,
                OutboxAggregateType.SAMPLE_ORDER,
                order.getId(),
                payload
        );
    }

    /** Each difference as a phrase a person can read without opening the app. */
    static List<String> describeChanges(OrderSnapshot before, OrderSnapshot after) {
        List<String> changes = new ArrayList<>();

        addChange(changes, "naziv", before.name(), after.name());
        addChange(changes, "kupac", before.customer(), after.customer());
        addChange(changes, "napomena", before.note(), after.note());
        addChange(changes, "datum", before.creationDate(), after.creationDate());
        addChange(changes, "rok", before.deadlineDate(), after.deadlineDate());
        addChange(changes, "napomena uz rok", before.deadlineNote(), after.deadlineNote());
        addChange(changes, "stavke", before.lineItems(), after.lineItems());

        return changes;
    }

    private static void addChange(List<String> changes, String label, Object before, Object after) {
        if (Objects.equals(before, after)) {
            return;
        }
        changes.add(label + ": " + describeValue(before) + " → " + describeValue(after));
    }

    /**
     * Values as they should read in a sentence, not as {@code toString} leaves
     * them: a bare "null" in a mail to the shop floor explains nothing.
     */
    private static String describeValue(Object value) {
        if (value == null) {
            return "nije postavljeno";
        }
        if (value instanceof Boolean flag) {
            return flag ? "da" : "ne";
        }
        if (value instanceof LocalDate date) {
            return DATE_FORMAT.format(date);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "nije postavljeno" : String.join(", ",
                    list.stream().map(String::valueOf).toList());
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "nije postavljeno" : text;
    }

    // ── Shared internals ────────────────────────────────────────────────────

    private SampleOrder loadOrder(Long id) {
        return sampleOrderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Nalog za izradu uzoraka nije pronađen (id=" + id + ")"));
    }

    /**
     * A closed order is a record, not a draft.
     *
     * <p>Refused here rather than left to the database, which has nothing to say
     * about it: the recipients already stop moving when the order closes, and an
     * order whose lines could still change afterwards would leave the mail people
     * received describing something that is no longer true.
     */
    private static void requireOpen(SampleOrder order) {
        if (SampleOrderStatus.isClosed(order.getStatus())) {
            throw new ConflictException("Nalog je zatvoren i više ne može da se menja.");
        }
    }

    /**
     * The rok cannot fall before the day the order was written.
     *
     * <p>{@code chk_sample_orders_valid_deadline} says the same thing and would
     * refuse the insert anyway — as a constraint-violation stack trace. Said here
     * so the person sees which two dates disagree.
     */
    private static void requireDeadlineNotBeforeCreation(LocalDate creationDate, LocalDate deadlineDate) {
        if (creationDate != null && deadlineDate != null && deadlineDate.isBefore(creationDate)) {
            throw new IllegalArgumentException(
                    "Rok (" + DATE_FORMAT.format(deadlineDate) + ") ne može biti pre datuma naloga ("
                            + DATE_FORMAT.format(creationDate) + ").");
        }
    }

    /**
     * The customer an order says it is for.
     *
     * <p>Null in means null out — samples made for an internal trial are for
     * nobody outside. A customer id that names nothing is a different matter and
     * is refused: silently booking the order against no customer would leave
     * somebody believing they had recorded one.
     *
     * <p>A DEACTIVATED customer is accepted here. The pickers only offer active
     * ones, but an order already booked against a customer who has since been
     * deactivated has to survive its next edit — refusing would make that order
     * unsaveable until somebody guessed why.
     */
    private Customer resolveCustomer(Long customerId) {
        if (customerId == null) return null;
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kupac nije pronađen (id=" + customerId + ")"));
    }

    /**
     * The mailing lists chosen while the order was being written.
     *
     * <p>Each one goes through the ordinary attach, so every rule that guards it
     * guards this too: read access to the list, the refusal of archived lists,
     * and the recipient de-duplication that stops one person on three lists
     * receiving three mails.
     *
     * <p><b>The permission is checked HERE and not inferred.</b> Attaching is
     * gated by SAMPLE_ORDER_RECIPIENT_MANAGE on the recipients controller, and
     * this path does not go through that controller. Today every role that may
     * create an order also holds it, which is exactly why it is written down: the
     * day somebody adds a role that may create orders and nothing else, creating
     * one must not quietly become a way to mail whoever is on a list.
     */
    private void attachRequestedMailingLists(Long orderId, List<Long> mailingListIds, Long actorId) {
        if (mailingListIds == null || mailingListIds.isEmpty()) {
            return;
        }

        if (!permissionService.hasPermission(AppPermission.SAMPLE_ORDER_RECIPIENT_MANAGE)) {
            throw new AccessDeniedException("Nemate ovlašćenje da određujete kome se nalog šalje.");
        }

        // Distinct, because the same list twice is a conflict the attach would
        // refuse — and refusing the whole order over a repeated pick in the form
        // would be punishing somebody for a double click.
        for (Long listId : mailingListIds.stream().filter(Objects::nonNull).distinct().toList()) {
            recipientService.attachMailingList(orderId, listId, actorId);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
