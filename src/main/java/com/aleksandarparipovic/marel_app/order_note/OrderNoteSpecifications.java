package com.aleksandarparipovic.marel_app.order_note;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * The where-clause of the wall, built on the server. Every narrowing a caller
 * can ask for — which order, whose notes, matching what text — is a
 * {@link Specification} here; the ordering and the page window are a
 * {@code Pageable}. Nothing is filtered or sorted in memory.
 */
public final class OrderNoteSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private OrderNoteSpecifications() {
    }

    /** The notes on one order of one kind — the scope every list starts from. */
    public static Specification<OrderNote> onParent(OrderNoteParentType type, Long orderId) {
        return (root, query, cb) -> switch (type) {
            case PRODUCTION -> cb.equal(root.get("productionOrder").get("id"), orderId);
            case SAMPLE -> cb.equal(root.get("sampleOrder").get("id"), orderId);
        };
    }

    /** Live notes only — an archived (deleted) note is off the wall. */
    public static Specification<OrderNote> active() {
        return (root, query, cb) -> cb.isTrue(root.get("isActive"));
    }

    /** Narrowed to one author, for the "samo ovaj kolega" filter. */
    public static Specification<OrderNote> byAuthor(Long authorId) {
        return (root, query, cb) -> cb.equal(root.get("author").get("id"), authorId);
    }

    /**
     * Notes whose flattened text contains {@code text}, case-insensitively and
     * part-way. Runs over body_text — the plain-text projection written beside
     * the document — never over the jsonb itself. Wildcards a person types are
     * escaped so "50%" searches for the two characters, not "50 then anything".
     */
    public static Specification<OrderNote> matchesText(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("bodyText")), pattern, LIKE_ESCAPE);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
