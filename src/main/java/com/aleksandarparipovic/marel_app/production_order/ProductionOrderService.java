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

        return productionOrderMapper.toDetailDto(order, deadlineDtos, lineItemDtos);
    }

    /**
     * Announces a moved delivery date, and only that.
     *
     * <p>Compared as an ordered list of value descriptions rather than by row id,
     * because update() never edits a deadline in place — it deactivates the old
     * rows and inserts new ones. By identity every save looks like a change; by
     * value, only a real one does.
     */
    private void publishDeadlineChange(ProductionOrder order, List<String> deadlinesBefore) {
        List<String> deadlinesAfter = describeDeadlines(
                productionOrderDeadlineRepository.findAllByProductionOrder_IdAndIsActiveIsTrue(order.getId()));

        if (deadlinesBefore.equals(deadlinesAfter)) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("deadlinesBefore", deadlinesBefore);
        payload.put("deadlinesAfter", deadlinesAfter);
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_CHANGED,
                OutboxAggregateType.PRODUCTION_ORDER,
                order.getId(),
                payload
        );
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

        // Read BEFORE the replace below. update() deactivates every deadline and
        // inserts a fresh set on every save, even when the form did not touch them,
        // so "were they changed" cannot be answered afterwards — and publishing
        // without asking would e-mail the whole recipient list every time somebody
        // fixes a note.
        List<String> deadlinesBefore = describeDeadlines(
                productionOrderDeadlineRepository.findAllByProductionOrder_IdAndIsActiveIsTrue(id));

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

        publishDeadlineChange(order, deadlinesBefore);

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
