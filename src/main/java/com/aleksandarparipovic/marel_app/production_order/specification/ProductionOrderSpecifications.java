package com.aleksandarparipovic.marel_app.production_order.specification;

import com.aleksandarparipovic.marel_app.customer.Customer;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderFieldMapper;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ProductionOrderSpecifications {

    private static final ProductionOrderFieldMapper FIELD_MAPPER = new ProductionOrderFieldMapper();
    private static final String PRIORITY_FLAGS_FIELD = "priorityFlags";

    /**
     * The escape character for the LIKE patterns below. Without one, a note
     * search for "50%" would ask the database for "50 followed by anything" —
     * every order — and the page would mark none of them, because the Java side
     * that decides WHICH note matched reads the typed text literally.
     */
    private static final char LIKE_ESCAPE = '\\';

    private ProductionOrderSpecifications() {
    }

    public static Specification<ProductionOrder> fromSearchRequest(SearchRequest request) {
        Specification<ProductionOrder> specification = Specification.where(notArchived());

        List<String> priorityFlags = extractPriorityFlags(request);
        if (!priorityFlags.isEmpty()) {
            specification = specification.and(priorityFlagsSpecification(priorityFlags));
        }

        return specification.and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<ProductionOrder> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }

    /** The orders booked against one customer. */
    public static Specification<ProductionOrder> forCustomer(Long customerId) {
        return (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
    }

    /**
     * Orders one free-text box finds, case-insensitively and part-way.
     *
     * <p><b>What is searched.</b> The order's own code, name and note; and, on
     * any of its live line items, the line's note, the notes in its note list,
     * and the product's name and code. One box rather than six, because the
     * person looking for "lakirati" or for "ACME-220" does not know — and should
     * not have to know — which field somebody typed it into.
     *
     * <p>The line items are reached by EXISTS rather than by a join. A join
     * would return the order once per matching line and turn the page count into
     * a count of lines; this asks the only question the filter has — is there one
     * — and leaves the paging alone.
     *
     * <p>The product is reached through the line item's own association, which is
     * an INNER join and correctly so: a line without a product cannot exist, so
     * there is nothing for it to drop.
     *
     * <p>Whether an order matched at all is decided HERE; WHICH line item matched
     * is decided in the service, on the same lower-case contains rule. The two
     * must agree, which is why the pattern below is escaped.
     */
    public static Specification<ProductionOrder> matchesText(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";

        return (root, query, cb) -> {
            Subquery<Integer> lineItemMatch = query.subquery(Integer.class);
            Root<ProductionOrderLineItem> lineItem = lineItemMatch.from(ProductionOrderLineItem.class);
            Path<?> product = lineItem.get("product");

            Subquery<Integer> lineItemNoteMatch = lineItemMatch.subquery(Integer.class);
            Root<ProductionOrderLineItemNote> lineItemNote = lineItemNoteMatch.from(ProductionOrderLineItemNote.class);
            lineItemNoteMatch.select(cb.literal(1)).where(
                    cb.equal(lineItemNote.get("productionOrderLineItem"), lineItem),
                    cb.isTrue(lineItemNote.get("isActive")),
                    like(cb, lineItemNote.get("note"), pattern)
            );

            lineItemMatch.select(cb.literal(1)).where(
                    cb.equal(lineItem.get("productionOrder"), root),
                    cb.isTrue(lineItem.get("isActive")),
                    cb.isNull(lineItem.get("archivedAt")),
                    cb.or(
                            like(cb, lineItem.get("note"), pattern),
                            like(cb, product.get("productName"), pattern),
                            like(cb, product.get("productCode"), pattern),
                            cb.exists(lineItemNoteMatch)
                    )
            );

            return cb.or(
                    like(cb, root.get("code"), pattern),
                    like(cb, root.get("name"), pattern),
                    like(cb, root.get("note"), pattern),
                    cb.exists(lineItemMatch)
            );
        };
    }

    /**
     * Everything that could identify an order, in one box.
     *
     * <p>{@link #matchesText} plus the fields the copy picker needs on top of
     * it: the CUSTOMER (name, code, tax id), the line's description for the shop
     * floor, and the quantity read as text. Somebody hunting for the order they
     * made last spring recognises it by whichever of those they happen to
     * remember, and the box does not ask them which.
     *
     * <p>Kept separate from {@link #matchesText} rather than folded into it. The
     * customer's own page searches with that one, where matching the customer's
     * name would match every row on the screen and matching a quantity would
     * turn "5" into a filter nobody meant.
     *
     * <p>The customer is reached by EXISTS on its id rather than by a join: the
     * id is already a column on this table, and an order for nobody outside must
     * not be dropped for having no customer to compare.
     */
    public static Specification<ProductionOrder> matchesAnything(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";

        Specification<ProductionOrder> extra = (root, query, cb) -> {
            Subquery<Integer> customerMatch = query.subquery(Integer.class);
            Root<Customer> customer = customerMatch.from(Customer.class);
            customerMatch.select(cb.literal(1)).where(
                    cb.equal(customer.get("id"), root.get("customer").get("id")),
                    cb.or(
                            like(cb, customer.get("name"), pattern),
                            like(cb, customer.get("code"), pattern),
                            like(cb, customer.get("taxId"), pattern)
                    )
            );

            Subquery<Integer> lineItemMatch = query.subquery(Integer.class);
            Root<ProductionOrderLineItem> lineItem = lineItemMatch.from(ProductionOrderLineItem.class);

            List<Predicate> onTheLine = new ArrayList<>();
            onTheLine.add(like(cb, lineItem.get("productDescription"), pattern));

            /*
             * THE QUANTITY IS MATCHED AS A NUMBER, NOT AS TEXT.
             *
             * Everything else here is a substring search, and for a quantity that
             * would be worse than useless: "5" would match 5, 15, 50, 512 and
             * 1500 — most of the table — while nobody hunting for an old order
             * ever remembers half of a number. Typing 120 means the line for 120
             * pieces.
             *
             * It is also the only rule PostgreSQL will accept without a cast:
             * `Path.as(String.class)` is a compile-time reinterpretation that
             * Hibernate renders as nothing, and the database then answers
             * "function lower(integer) does not exist".
             *
             * Both the line's own total and its individual delivery quantities
             * count, so 120 finds a line delivered all at once AND one delivered
             * as 50 + 120.
             */
            Integer number = parseSearchNumber(text);
            if (number != null) {
                Subquery<Integer> quantityMatch = lineItemMatch.subquery(Integer.class);
                Root<ProductionOrderLineItemQuantity> quantity =
                        quantityMatch.from(ProductionOrderLineItemQuantity.class);
                quantityMatch.select(cb.literal(1)).where(
                        cb.equal(quantity.get("productionOrderLineItem"), lineItem),
                        cb.isTrue(quantity.get("isActive")),
                        cb.equal(quantity.get("quantity"), number)
                );

                onTheLine.add(cb.equal(lineItem.get("quantity"), number));
                onTheLine.add(cb.exists(quantityMatch));
            }

            lineItemMatch.select(cb.literal(1)).where(
                    cb.equal(lineItem.get("productionOrder"), root),
                    cb.isTrue(lineItem.get("isActive")),
                    cb.isNull(lineItem.get("archivedAt")),
                    cb.or(onTheLine.toArray(new Predicate[0]))
            );

            return cb.or(cb.exists(customerMatch), cb.exists(lineItemMatch));
        };

        return matchesText(text).or(extra);
    }

    /** Orders booked against one customer, for the copy picker's filter. */
    public static Specification<ProductionOrder> customerIs(Long customerId) {
        return forCustomer(customerId);
    }

    /** Orders written by one person. */
    public static Specification<ProductionOrder> writtenBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    /**
     * Orders created within a span of days, both ends INCLUSIVE.
     *
     * <p>Inclusive because the span is spoken as "from the 3rd to the 5th", and
     * because a single day is a span people ask for — from and to the same date
     * has to mean that day rather than nothing at all.
     *
     * <p>Either end may be null for an open span. An order with no creation date
     * matches neither, which is right: it cannot be shown to fall inside a span
     * when nothing says when it happened.
     */
    public static Specification<ProductionOrder> createdBetween(LocalDate from, LocalDate to) {
        LocalDate start = (from != null && to != null && from.isAfter(to)) ? to : from;
        LocalDate end = (from != null && to != null && from.isAfter(to)) ? from : to;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (start != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("creationDate"), start));
            }
            if (end != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("creationDate"), end));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The searched text as a whole number, or null when it is not one.
     *
     * <p>Public because the SQL filter and the Java that MARKS what matched must
     * read a quantity the same way. Two rules that disagree would return a line
     * and then decline to say why.
     */
    public static Integer parseSearchNumber(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 9) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return null;
        }
        return Integer.valueOf(trimmed);
    }

    /** Whether one stored value contains the searched text, by the rule above. */
    public static boolean containsText(String value, String text) {
        return value != null
                && text != null
                && value.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private static Predicate like(CriteriaBuilder cb, Expression<?> expression, String pattern) {
        return cb.like(cb.lower(expression.as(String.class)), pattern, LIKE_ESCAPE);
    }

    /** Wildcards a person typed are text, not pattern. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> extractPriorityFlags(SearchRequest request) {
        if (request == null || request.getFilters() == null) {
            return List.of();
        }

        List<String> flags = List.of();
        List<SearchRequest.FilterField> remaining = new ArrayList<>();

        for (SearchRequest.FilterField filter : request.getFilters()) {
            if (filter != null && PRIORITY_FLAGS_FIELD.equals(filter.getField())) {
                flags = toStringList(filter.getValue());
            } else {
                remaining.add(filter);
            }
        }

        request.setFilters(remaining);
        return flags;
    }

    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String s) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
        }
        return List.of();
    }

    private static Specification<ProductionOrder> priorityFlagsSpecification(List<String> flags) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String flag : flags) {
                switch (flag) {
                    case "HIGH_PRIORITY" -> predicates.add(cb.isTrue(root.get("isHighPriority")));
                    case "ANNOUNCED" -> predicates.add(cb.isTrue(root.get("isAnnounced")));
                    case "SUCCESSIVE_DELIVERIES" -> predicates.add(cb.isTrue(root.get("hasSuccessiveDeliveries")));
                    default -> throw new IllegalArgumentException("Unknown priority flag: " + flag);
                }
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.or(predicates.toArray(new Predicate[0]));
        };
    }
}
