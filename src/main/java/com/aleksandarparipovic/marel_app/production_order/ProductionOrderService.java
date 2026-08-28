package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDeadlineDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order.specification.ProductionOrderSpecifications;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemNoteDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemQuantityDto;
import com.aleksandarparipovic.marel_app.production_order_deadline.ProductionOrderDeadline;
import com.aleksandarparipovic.marel_app.production_order_deadline.repository.ProductionOrderDeadlineRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.repository.ProductionOrderLineItemNoteRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.repository.ProductionOrderLineItemQuantityRepository;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.customer.Customer;
import com.aleksandarparipovic.marel_app.customer.CustomerRepository;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipientService;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionOrderService {

    private static final String DEADLINE_SORT_FIELD = "deliveryDeadline";

    /** Serbian date order, the one used everywhere else people read dates here. */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderDeadlineRepository productionOrderDeadlineRepository;
    private final ProductionOrderLineItemRepository productionOrderLineItemRepository;
    private final ProductionOrderLineItemQuantityRepository productionOrderLineItemQuantityRepository;
    private final ProductionOrderLineItemNoteRepository productionOrderLineItemNoteRepository;
    private final ProductionOrderMapper productionOrderMapper;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final ProductionOrderRecipientService recipientService;
    private final PermissionService permissionService;
    private final OutboxEventPublisher outboxEventPublisher;

    @Transactional
    public ProductionOrderDetailDto create(ProductionOrderCreateRequest req) {
        Long userId = currentUserService.getCurrentUserId();
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;

        ProductionOrder order = new ProductionOrder();
        order.setCode(req.code().trim());
        order.setName(req.name().trim());
        order.setNote(req.note());
        order.setTestingRequired(Boolean.TRUE.equals(req.testingRequired()));
        order.setCreationDate(req.creationDate());
        order.setOrderDate(req.orderDate());
        order.setDeliveryDeadline(req.deliveryDeadline());
        order.setIsHighPriority(Boolean.TRUE.equals(req.isHighPriority()));
        order.setIsAnnounced(Boolean.TRUE.equals(req.isAnnounced()));
        order.setHasSuccessiveDeliveries(Boolean.TRUE.equals(req.hasSuccessiveDeliveries()));
        order.setStatus(ProductionOrderStatus.CREATED);
        order.setIsActive(true);
        order.setUser(user);
        order.setCustomer(resolveCustomer(req.customerId()));
        order = productionOrderRepository.save(order);

        // AFTER the save, because a snapshot needs the order's id — but still
        // inside this transaction, so an order never survives with a list
        // attached and no recipients copied, nor the other way round.
        attachRequestedMailingLists(order.getId(), req.mailingListIds(), userId);

        // deadlines
        List<ProductionOrderDeadlineDto> deadlineDtos = new ArrayList<>();
        if (req.deadlines() != null) {
            for (int i = 0; i < req.deadlines().size(); i++) {
                var d = req.deadlines().get(i);
                ProductionOrderDeadline deadline = new ProductionOrderDeadline();
                deadline.setProductionOrder(order);
                deadline.setDeadlineOrder(i + 1);
                deadline.setDeadlineDateFrom(d.deadlineDateFrom());
                deadline.setDeadlineDateTo(d.deadlineDateTo());
                deadline.setQuantity(d.quantity());
                deadline.setIsActive(true);
                deadlineDtos.add(productionOrderMapper.toDeadlineDto(
                        productionOrderDeadlineRepository.save(deadline)));
            }
        }

        // line items
        List<ProductionOrderLineItemDto> lineItemDtos = new ArrayList<>();
        if (req.lineItems() != null) {
            for (var itemReq : req.lineItems()) {
                Product product = productRepository.findById(itemReq.productId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Proizvod nije pronađen (id=" + itemReq.productId() + ")"));

                int totalQty = itemReq.quantities() == null ? 0 :
                        itemReq.quantities().stream()
                                .mapToInt(q -> q.quantity() != null ? q.quantity() : 0)
                                .sum();

                ProductionOrderLineItem lineItem = new ProductionOrderLineItem();
                lineItem.setProductionOrder(order);
                lineItem.setProduct(product);
                lineItem.setQuantity(totalQty);
                lineItem.setProductDescription(itemReq.description());
                lineItem.setNote(itemReq.note());
                lineItem.setLineOrder(itemReq.lineOrder() != null ? itemReq.lineOrder() : 1);
                lineItem.setIsActive(true);
                lineItem = productionOrderLineItemRepository.save(lineItem);

                List<ProductionOrderLineItemQuantityDto> quantityDtos = new ArrayList<>();
                if (itemReq.quantities() != null) {
                    for (int j = 0; j < itemReq.quantities().size(); j++) {
                        var qReq = itemReq.quantities().get(j);
                        ProductionOrderLineItemQuantity qty = new ProductionOrderLineItemQuantity();
                        qty.setProductionOrderLineItem(lineItem);
                        qty.setOrderQuantity(j + 1);
                        qty.setQuantity(qReq.quantity());
                        qty.setDeliveryDeadline(qReq.deliveryDeadline());
                        qty.setIsActive(true);
                        quantityDtos.add(productionOrderMapper.toLineItemQuantityDto(
                                productionOrderLineItemQuantityRepository.save(qty)));
                    }
                }

                lineItemDtos.add(productionOrderMapper.toLineItemDto(lineItem, quantityDtos, List.of()));
            }
        }

        // LAST, and still inside this transaction. Last because the mail this
        // event sends opens the order's conversation and should describe an order
        // that is fully written, not one still being assembled. Inside, because
        // OutboxEventPublisher is MANDATORY: an announcement must not survive a
        // rollback of the thing it announces.
        publishOrderCreated(order);

        return productionOrderMapper.toDetailDto(order, deadlineDtos, lineItemDtos);
    }

    /**
     * Opens the order's e-mail conversation.
     *
     * <p>Every later notification about this order is sent as a reply to the mail
     * this event produces, which is why it goes out even though nothing has
     * happened yet: without a first message there is nothing for the rest to hang
     * off, and each change would arrive as an unrelated mail.
     *
     * <p>Recipients come from the snapshot that {@code attachRequestedMailingLists}
     * has already written above. An order created without a mailing list has none,
     * and then no mail is queued and no conversation is opened — the first change
     * that does have recipients opens it instead.
     */
    private void publishOrderCreated(ProductionOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                OutboxEventType.PRODUCTION_ORDER_CREATED,
                OutboxAggregateType.PRODUCTION_ORDER,
                order.getId(),
                payload
        );
    }

    /**
     * Everything about an order that a recipient would want to be told changed.
     *
     * <p>Deadlines and line items are held as ordered lists of VALUE descriptions
     * rather than rows, because update() never edits either in place — it
     * deactivates the old ones and inserts a fresh set on every save. By identity
     * every save looks like a change; by value, only a real one does.
     */
    record OrderSnapshot(
            String name,
            String note,
            String customer,
            LocalDate creationDate,
            LocalDate orderDate,
            String deliveryDeadline,
            Boolean testingRequired,
            Boolean highPriority,
            Boolean announced,
            Boolean successiveDeliveries,
            List<String> deadlines,
            List<String> lineItems
    ) {
    }

    private OrderSnapshot snapshot(ProductionOrder order) {
        return new OrderSnapshot(
                order.getName(),
                order.getNote(),
                order.getCustomer() == null ? null : order.getCustomer().getName(),
                order.getCreationDate(),
                order.getOrderDate(),
                order.getDeliveryDeadline(),
                order.getTestingRequired(),
                order.getIsHighPriority(),
                order.getIsAnnounced(),
                order.getHasSuccessiveDeliveries(),
                describeDeadlines(productionOrderDeadlineRepository
                        .findAllByProductionOrder_IdAndIsActiveIsTrue(order.getId())),
                describeLineItems(order.getId()));
    }

    /**
     * Line items as one description per line, quantities included.
     *
     * <p>Ordered by {@code lineOrder} so that reordering the same products reads
     * as a change — because it is one: the order of lines is what the shop floor
     * works through.
     */
    private List<String> describeLineItems(Long orderId) {
        return productionOrderLineItemRepository
                .findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(orderId)
                .stream()
                .map(item -> {
                    String product = item.getProduct() == null
                            ? "-" : item.getProduct().getProductName();
                    String quantities = productionOrderLineItemQuantityRepository
                            .findByProductionOrderLineItem_IdOrderByOrderQuantityAsc(item.getId())
                            .stream()
                            .filter(q -> Boolean.TRUE.equals(q.getIsActive()))
                            .map(q -> String.valueOf(q.getQuantity()))
                            .collect(Collectors.joining("+"));

                    return product + " (" + (quantities.isEmpty()
                            ? item.getQuantity() + " kom" : quantities + " kom") + ")";
                })
                .toList();
    }

    /**
     * Announces what this save actually changed — one message listing every
     * difference, not one message per field.
     *
     * <p>One save is one thing that happened, and the recipients are reading a
     * conversation. Splitting a single edit into five mails would put five
     * messages in the thread for one action and bury the one that mattered.
     *
     * <p>Nothing is published when nothing changed. update() rewrites deadlines
     * and line items on every save whether or not the form touched them, so
     * without the comparison every corrected typo would mail the whole recipient
     * list.
     */
    private void publishOrderUpdated(ProductionOrder order, OrderSnapshot before) {
        List<String> changes = describeChanges(before, snapshot(order));
        if (changes.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("changes", changes);
        payload.put("responsibleUserId",
                order.getUser() == null ? null : order.getUser().getId());
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                OutboxEventType.PRODUCTION_ORDER_UPDATED,
                OutboxAggregateType.PRODUCTION_ORDER,
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
        addChange(changes, "datum kreiranja", before.creationDate(), after.creationDate());
        addChange(changes, "datum porudžbine", before.orderDate(), after.orderDate());
        addChange(changes, "rok isporuke", before.deliveryDeadline(), after.deliveryDeadline());
        addChange(changes, "testiranje", before.testingRequired(), after.testingRequired());
        addChange(changes, "prioritet", before.highPriority(), after.highPriority());
        addChange(changes, "najava", before.announced(), after.announced());
        addChange(changes, "sukcesivne isporuke",
                before.successiveDeliveries(), after.successiveDeliveries());
        addChange(changes, "rokovi", before.deadlines(), after.deadlines());
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
     * them: a bare "true" or "null" in a mail to the shop floor explains nothing.
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

    /**
     * The deadlines as a person would read them, in display order — the form's
     * own ordering, which is what "the deadline changed" means to a recipient.
     */
    private List<String> describeDeadlines(List<ProductionOrderDeadline> deadlines) {
        return deadlines.stream()
                .sorted(Comparator.comparing(ProductionOrderDeadline::getDeadlineOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(d -> {
                    String from = d.getDeadlineDateFrom() == null ? "" : DATE_FORMAT.format(d.getDeadlineDateFrom());
                    String to = d.getDeadlineDateTo() == null ? "" : DATE_FORMAT.format(d.getDeadlineDateTo());
                    String range = from.isEmpty() ? to : from + " - " + to;
                    return d.getQuantity() == null ? range : range + " (" + d.getQuantity() + " kom)";
                })
                .toList();
    }

    /**
     * Full edit of an existing order — everything except the code (immutable once
     * created) and status (changed only via markDelivered). Deadlines and line items
     * (with their quantities) are never overwritten in place: the currently active
     * ones are deactivated and a fresh set is inserted from the submitted form, same
     * as create(). This preserves the old rows for audit instead of mutating them,
     * matching the isActive soft-delete convention already used throughout this table
     * group.
     */
    @Transactional
    public ProductionOrderDetailDto update(Long id, ProductionOrderUpdateRequest req) {
        ProductionOrder order = productionOrderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Proizvodni nalog nije pronađen (id=" + id + ")"));

        // BEFORE the first setter. The scalars below are mutated on the managed
        // entity in place, so a snapshot taken any later would already be the new
        // values compared against themselves — every save would report no change.
        OrderSnapshot before = snapshot(order);

        order.setName(req.name().trim());
        order.setNote(req.note());
        order.setTestingRequired(Boolean.TRUE.equals(req.testingRequired()));
        order.setCreationDate(req.creationDate());
        order.setOrderDate(req.orderDate());
        order.setDeliveryDeadline(req.deliveryDeadline());
        order.setIsHighPriority(Boolean.TRUE.equals(req.isHighPriority()));
        order.setIsAnnounced(Boolean.TRUE.equals(req.isAnnounced()));
        order.setHasSuccessiveDeliveries(Boolean.TRUE.equals(req.hasSuccessiveDeliveries()));
        // Null clears it, as with every other field on this form: the client
        // sends the whole order back on every save.
        order.setCustomer(resolveCustomer(req.customerId()));
        order = productionOrderRepository.save(order);

        for (ProductionOrderDeadline existing : productionOrderDeadlineRepository.findAllByProductionOrder_IdAndIsActiveIsTrue(id)) {
            existing.setIsActive(false);
            productionOrderDeadlineRepository.save(existing);
        }
        for (ProductionOrderLineItem existing : productionOrderLineItemRepository.findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(id)) {
            for (ProductionOrderLineItemQuantity existingQty : productionOrderLineItemQuantityRepository.findByProductionOrderLineItem_IdOrderByOrderQuantityAsc(existing.getId())) {
                if (Boolean.TRUE.equals(existingQty.getIsActive())) {
                    existingQty.setIsActive(false);
                    productionOrderLineItemQuantityRepository.save(existingQty);
                }
            }
            existing.setIsActive(false);
            productionOrderLineItemRepository.save(existing);
        }

        List<ProductionOrderDeadlineDto> deadlineDtos = new ArrayList<>();
        if (req.deadlines() != null) {
            for (int i = 0; i < req.deadlines().size(); i++) {
                var d = req.deadlines().get(i);
                ProductionOrderDeadline deadline = new ProductionOrderDeadline();
                deadline.setProductionOrder(order);
                deadline.setDeadlineOrder(i + 1);
                deadline.setDeadlineDateFrom(d.deadlineDateFrom());
                deadline.setDeadlineDateTo(d.deadlineDateTo());
                deadline.setQuantity(d.quantity());
                deadline.setIsActive(true);
                deadlineDtos.add(productionOrderMapper.toDeadlineDto(
                        productionOrderDeadlineRepository.save(deadline)));
            }
        }

        List<ProductionOrderLineItemDto> lineItemDtos = new ArrayList<>();
        if (req.lineItems() != null) {
            for (var itemReq : req.lineItems()) {
                Product product = productRepository.findById(itemReq.productId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Proizvod nije pronađen (id=" + itemReq.productId() + ")"));

                int totalQty = itemReq.quantities() == null ? 0 :
                        itemReq.quantities().stream()
                                .mapToInt(q -> q.quantity() != null ? q.quantity() : 0)
                                .sum();

                ProductionOrderLineItem lineItem = new ProductionOrderLineItem();
                lineItem.setProductionOrder(order);
                lineItem.setProduct(product);
                lineItem.setQuantity(totalQty);
                lineItem.setProductDescription(itemReq.description());
                lineItem.setNote(itemReq.note());
                lineItem.setLineOrder(itemReq.lineOrder() != null ? itemReq.lineOrder() : 1);
                lineItem.setIsActive(true);
                lineItem = productionOrderLineItemRepository.save(lineItem);

                List<ProductionOrderLineItemQuantityDto> quantityDtos = new ArrayList<>();
                if (itemReq.quantities() != null) {
                    for (int j = 0; j < itemReq.quantities().size(); j++) {
                        var qReq = itemReq.quantities().get(j);
                        ProductionOrderLineItemQuantity qty = new ProductionOrderLineItemQuantity();
                        qty.setProductionOrderLineItem(lineItem);
                        qty.setOrderQuantity(j + 1);
                        qty.setQuantity(qReq.quantity());
                        qty.setDeliveryDeadline(qReq.deliveryDeadline());
                        qty.setIsActive(true);
                        quantityDtos.add(productionOrderMapper.toLineItemQuantityDto(
                                productionOrderLineItemQuantityRepository.save(qty)));
                    }
                }

                lineItemDtos.add(productionOrderMapper.toLineItemDto(lineItem, quantityDtos, List.of()));
            }
        }

        publishOrderUpdated(order, before);

        return productionOrderMapper.toDetailDto(order, deadlineDtos, lineItemDtos);
    }

    /**
     * Marks an order as delivered. Simple one-way status flip — the enum only has
     * CREATED/DELIVERED, so there's no separate "status" endpoint to keep minimal.
     */
    @Transactional
    public ProductionOrderDetailDto markDelivered(Long id) {
        ProductionOrder order = productionOrderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Proizvodni nalog nije pronađen (id=" + id + ")"));

        boolean alreadyDelivered = order.getStatus() == ProductionOrderStatus.DELIVERED;

        order.setStatus(ProductionOrderStatus.DELIVERED);
        order = productionOrderRepository.save(order);

        // Published only on the actual transition. Outbox idempotency is keyed on
        // the outbox row, so re-marking a delivered order would otherwise create a
        // second event and notify everybody again.
        if (!alreadyDelivered) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderCode", order.getCode());
            payload.put("orderName", order.getName());
            payload.put("responsibleUserId",
                    order.getUser() == null ? null : order.getUser().getId());
            payload.put("actorUserId", currentUserService.getCurrentUserId());

            outboxEventPublisher.publish(
                    OutboxEventType.PRODUCTION_ORDER_COMPLETED,
                    OutboxAggregateType.PRODUCTION_ORDER,
                    order.getId(),
                    payload
            );
        }

        return getDetail(order.getId());
    }

    List<ProductionOrderOptionDto> getAllActiveProductionOrders(){
        return productionOrderRepository.findByIsActiveIsTrueOrderByNameAsc()
                .stream()
                .map(productionOrderMapper::toOptionDto)
                .toList();
    }

    public Page<ProductionOrderCardRow> searchAll(SearchRequest request) {
        Specification<ProductionOrder> specification = ProductionOrderSpecifications.fromSearchRequest(request);
        SearchRequest.Direction deadlineSortDirection = extractAndStripDeadlineSort(request);

        if (deadlineSortDirection != null) {
            return searchAllSortedByEffectiveDeadline(specification, request, deadlineSortDirection);
        }

        Pageable pageable = PageableBuilder.from(request);
        Page<ProductionOrder> page = productionOrderRepository.findAll(specification, pageable);
        DeadlineContext context = loadDeadlineContext(page.getContent());
        List<ProductionOrderCardRow> rows = buildCardRows(page.getContent(), context);

        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    private Page<ProductionOrderCardRow> searchAllSortedByEffectiveDeadline(
            Specification<ProductionOrder> specification,
            SearchRequest request,
            SearchRequest.Direction direction
    ) {
        List<ProductionOrder> allMatching = productionOrderRepository.findAll(specification);
        DeadlineContext context = loadDeadlineContext(allMatching);

        Comparator<LocalDate> dateComparator = direction == SearchRequest.Direction.DESC
                ? Comparator.<LocalDate>reverseOrder()
                : Comparator.<LocalDate>naturalOrder();

        Comparator<ProductionOrder> comparator = Comparator
                .comparing((ProductionOrder order) -> !Boolean.TRUE.equals(order.getIsHighPriority()))
                .thenComparing(
                        order -> context.effectiveByOrder().getOrDefault(order.getId(), EffectiveDeadline.EMPTY).date(),
                        Comparator.nullsLast(dateComparator)
                )
                .thenComparing(ProductionOrder::getId);

        List<ProductionOrder> sorted = allMatching.stream().sorted(comparator).toList();

        Pageable pageable = PageableBuilder.from(request);
        int fromIndex = Math.min(pageable.getPageNumber() * pageable.getPageSize(), sorted.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), sorted.size());
        List<ProductionOrder> pageContent = sorted.subList(fromIndex, toIndex);

        List<ProductionOrderCardRow> rows = buildCardRows(pageContent, context);
        return new PageImpl<>(rows, pageable, sorted.size());
    }

    private SearchRequest.Direction extractAndStripDeadlineSort(SearchRequest request) {
        if (request == null || request.getSort() == null) {
            return null;
        }

        SearchRequest.Direction[] found = new SearchRequest.Direction[1];
        List<SearchRequest.SortField> remaining = new ArrayList<>();

        for (SearchRequest.SortField sortField : request.getSort()) {
            if (sortField != null && DEADLINE_SORT_FIELD.equals(sortField.getField())) {
                found[0] = sortField.getDirection();
            } else {
                remaining.add(sortField);
            }
        }

        request.setSort(remaining);
        return found[0];
    }

    private record EffectiveDeadline(LocalDate date, boolean fromLineItem) {
        static final EffectiveDeadline EMPTY = new EffectiveDeadline(null, false);
    }

    private record DeadlineContext(
            Map<Long, List<ProductionOrderDeadline>> deadlinesByOrder,
            Map<Long, EffectiveDeadline> effectiveByOrder
    ) {
    }

    private DeadlineContext loadDeadlineContext(List<ProductionOrder> orders) {
        List<Long> orderIds = orders.stream().map(ProductionOrder::getId).toList();

        Map<Long, List<ProductionOrderDeadline>> deadlinesByOrder = productionOrderDeadlineRepository
                .findByProductionOrder_IdInOrderByDeadlineOrderAsc(orderIds)
                .stream()
                .collect(Collectors.groupingBy(d -> d.getProductionOrder().getId()));

        List<ProductionOrderLineItem> lineItems = productionOrderLineItemRepository
                .findByProductionOrder_IdInAndIsActiveIsTrue(orderIds);
        Map<Long, List<ProductionOrderLineItem>> lineItemsByOrder = lineItems.stream()
                .collect(Collectors.groupingBy(li -> li.getProductionOrder().getId()));
        List<Long> lineItemIds = lineItems.stream().map(ProductionOrderLineItem::getId).toList();

        Map<Long, List<ProductionOrderLineItemQuantity>> quantitiesByLineItem = productionOrderLineItemQuantityRepository
                .findByProductionOrderLineItem_IdInAndIsActiveIsTrue(lineItemIds)
                .stream()
                .collect(Collectors.groupingBy(q -> q.getProductionOrderLineItem().getId()));

        Map<Long, EffectiveDeadline> effectiveByOrder = new HashMap<>();
        for (ProductionOrder order : orders) {
            LocalDate orderNearest = deadlinesByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .filter(d -> Boolean.TRUE.equals(d.getIsActive()))
                    .map(ProductionOrderDeadline::getDeadlineDateTo)
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            LocalDate itemNearest = lineItemsByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .flatMap(li -> quantitiesByLineItem.getOrDefault(li.getId(), List.of()).stream())
                    .map(ProductionOrderLineItemQuantity::getDeliveryDeadline)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            boolean fromLineItem = itemNearest != null && (orderNearest == null || itemNearest.isBefore(orderNearest));
            LocalDate effective = fromLineItem ? itemNearest : orderNearest;
            effectiveByOrder.put(order.getId(), new EffectiveDeadline(effective, fromLineItem));
        }

        return new DeadlineContext(deadlinesByOrder, effectiveByOrder);
    }

    private List<ProductionOrderCardRow> buildCardRows(List<ProductionOrder> orders, DeadlineContext context) {
        return orders.stream()
                .map(order -> {
                    List<ProductionOrderDeadlineDto> deadlineDtos = context.deadlinesByOrder()
                            .getOrDefault(order.getId(), List.of())
                            .stream()
                            .map(productionOrderMapper::toDeadlineDto)
                            .toList();
                    EffectiveDeadline effective = context.effectiveByOrder()
                            .getOrDefault(order.getId(), EffectiveDeadline.EMPTY);
                    return productionOrderMapper.toCardRow(order, deadlineDtos, effective.date(), effective.fromLineItem());
                })
                .toList();
    }

    public ProductionOrderDetailDto getDetail(Long id) {
        ProductionOrder order = productionOrderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Proizvodni nalog nije pronađen (id=" + id + ")"));

        List<ProductionOrderDeadlineDto> deadlines = productionOrderDeadlineRepository
                .findByProductionOrder_IdOrderByDeadlineOrderAsc(id)
                .stream()
                .map(productionOrderMapper::toDeadlineDto)
                .toList();

        List<ProductionOrderLineItem> activeLineItems = productionOrderLineItemRepository
                .findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(id);

        List<ProductionOrderLineItemDto> lineItems = activeLineItems.stream()
                .map(lineItem -> {
                    List<ProductionOrderLineItemQuantityDto> quantities = productionOrderLineItemQuantityRepository
                            .findByProductionOrderLineItem_IdOrderByOrderQuantityAsc(lineItem.getId())
                            .stream()
                            .filter(q -> Boolean.TRUE.equals(q.getIsActive()))
                            .map(productionOrderMapper::toLineItemQuantityDto)
                            .toList();
                    List<ProductionOrderLineItemNoteDto> notes = productionOrderLineItemNoteRepository
                            .findByProductionOrderLineItem_IdOrderByOrderNoteAsc(lineItem.getId())
                            .stream()
                            .map(productionOrderMapper::toLineItemNoteDto)
                            .toList();
                    return productionOrderMapper.toLineItemDto(lineItem, quantities, notes);
                })
                .toList();

        return productionOrderMapper.toDetailDto(order, deadlines, lineItems);
    }

    /**
     * The customer an order says it is for.
     *
     * <p>Null in means null out — an order for nobody outside is the ordinary
     * case, not a missing value. A customer id that names nothing is a different
     * matter and is refused: silently booking the order against no customer
     * would leave somebody believing they had recorded one.
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
     * gated by PRODUCTION_ORDER_RECIPIENT_MANAGE on the recipients controller,
     * and this path does not go through that controller. Today every role that
     * may create an order also holds it, so the check never fires — which is
     * exactly why it is written down: the day somebody adds a role that may
     * create orders and nothing else, creating one must not quietly become a way
     * to mail whoever is on a list.
     */
    private void attachRequestedMailingLists(Long orderId, List<Long> mailingListIds, Long actorId) {
        if (mailingListIds == null || mailingListIds.isEmpty()) {
            return;
        }

        if (!permissionService.hasPermission(AppPermission.PRODUCTION_ORDER_RECIPIENT_MANAGE)) {
            throw new AccessDeniedException(
                    "Nemate ovlašćenje da određujete kome se nalog šalje.");
        }

        // Distinct, because the same list twice is a conflict the attach would
        // refuse — and refusing the whole order over a repeated pick in the form
        // would be punishing somebody for a double click.
        for (Long listId : mailingListIds.stream().filter(Objects::nonNull).distinct().toList()) {
            recipientService.attachMailingList(orderId, listId, actorId);
        }
    }
}
