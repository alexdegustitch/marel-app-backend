package com.aleksandarparipovic.marel_app.work_code.dto;

import java.util.List;

/**
 * The šifarnik's drag-order: visible row ids top to bottom. The first code gets
 * {@code display_order} 5, the next 10, and so on — and every VERSION of a code
 * gets its chain's value, so the order cannot disagree between versions.
 */
public record ReorderWorkCodeCategoriesRequest(List<Long> orderedIds) {
}
