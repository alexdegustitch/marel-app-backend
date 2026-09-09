package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * What the manufacturing-time lists may filter and sort by.
 *
 * <p>Every field lives on the record itself — the product's name is denormalised
 * onto the row at save time — so nothing here needs a join, and the same names
 * work verbatim as JPA sort properties.
 */
public final class ProductManufacturingTimeFieldMapper
        implements EntityFieldMapper<ProductManufacturingTime> {

    private static final Map<String, BiFunction<Root<ProductManufacturingTime>, CriteriaBuilder, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("id", (root, cb) -> root.get("id")),
                    Map.entry("title", (root, cb) -> root.get("title")),
                    Map.entry("productName", (root, cb) -> root.get("productName")),
                    // The FK read off the relation directly — no join for an equality check.
                    Map.entry("productId", (root, cb) -> root.get("product").get("id")),
                    Map.entry("dateOfIssue", (root, cb) -> root.get("dateOfIssue")),
                    Map.entry("createdAt", (root, cb) -> root.get("createdAt")),
                    Map.entry("manufacturingTimeSeconds", (root, cb) -> root.get("manufacturingTimeSeconds")),
                    Map.entry("productsPerHour", (root, cb) -> root.get("productsPerHour"))
            );

    @Override
    public Path<?> resolvePath(
            String fieldName,
            Root<ProductManufacturingTime> root,
            CriteriaBuilder cb,
            JoinManager<ProductManufacturingTime> joinManager
    ) {
        BiFunction<Root<ProductManufacturingTime>, CriteriaBuilder, Path<?>> resolver = FIELD_MAP.get(fieldName);
        if (resolver == null) {
            throw new IllegalArgumentException("Invalid filter field: " + fieldName);
        }
        return resolver.apply(root, cb);
    }

    @Override
    public List<String> getGlobalSearchFields() {
        return List.of("title", "productName");
    }
}
