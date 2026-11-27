package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttribute;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOption;
import jakarta.persistence.*;
import lombok.*;

/**
 * Which option a variant picked for one attribute.
 *
 * <p>The attribute is stored DENORMALIZED on purpose: it lets the database
 * itself enforce "one option per attribute within a variant" (unique) and
 * "the option really belongs to that attribute" (composite FK) — see V76.
 */
@Entity
@Table(name = "product_variant_values")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariantValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_attribute_id", nullable = false)
    private VariantAttribute attribute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_attribute_option_id", nullable = false)
    private VariantAttributeOption option;
}
