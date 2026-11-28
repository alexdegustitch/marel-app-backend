package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.product.Product;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One concrete combination of variant-attribute options for one product:
 * "IVS PA 450 · Prohrom / Al".
 *
 * <p><b>Frozen once used</b> (SPEC odluka 13): as soon as an order line or a
 * manufacturing time references it, its VALUES stop being editable — a changed
 * combination is a NEW variant. Only the label stays free. The service holds
 * that line; the database holds the rest: one option per attribute, the option
 * belongs to the attribute, no two identical live combinations
 * (uq_product_variants_signature on the canonical {@link #signature}).
 */
@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Optional hand-given name; NULL means the UI composes one from the values. */
    @Column(name = "label")
    private String label;

    /** Canonical combination: option ids sorted ascending, joined with "|". */
    @Column(name = "signature", nullable = false)
    private String signature;

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProductVariantValue> values = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * What a screen calls this variant: the hand-given label, or the option
     * values joined ("Prohrom / Al"). Touches the lazy values, so callers sit
     * inside a transaction — which every mapper here does.
     */
    public String displayLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return values.stream()
                .sorted(java.util.Comparator
                        .comparing((ProductVariantValue v) -> v.getAttribute().getSortOrder())
                        .thenComparing(v -> v.getAttribute().getName()))
                .map(v -> v.getOption().getValue())
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * The same combination spelled out attribute by attribute:
     * "Šraf: Al · Navrtka: Prohrom".
     *
     * <p>Deliberately NOT the hand-given label, and deliberately separate from
     * {@link #displayLabel()}: a screen that shows the variant after a product
     * name needs to say WHICH attribute each value belongs to, and a label like
     * "Varijanta 2" would say nothing. Null when the variant carries no values,
     * so a caller can leave the line out rather than print an empty tail.
     *
     * <p>Touches the lazy values, so callers sit inside a transaction.
     */
    public String attributesLabel() {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream()
                .sorted(java.util.Comparator
                        .comparing((ProductVariantValue v) -> v.getAttribute().getSortOrder())
                        .thenComparing(v -> v.getAttribute().getName()))
                .map(v -> v.getAttribute().getName() + ": " + v.getOption().getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    public void archive() {
        this.isActive = false;
        this.archivedAt = OffsetDateTime.now();
    }

    public void restore() {
        this.isActive = true;
        this.archivedAt = null;
    }
}
