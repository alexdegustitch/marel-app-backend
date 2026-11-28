package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttribute;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOption;
import jakarta.persistence.*;
import lombok.*;

/**
 * The BINDING of an operation: the attribute value it exists for.
 *
 * <p>"Montaža navrtke Al" is the pair (product, Al) — one operation shared by
 * EVERY variant of the product that carries Al, today's and any future one,
 * with one norm and one history. An operation with no binding rows applies to
 * every variant of its product, which is what every pre-V77 operation means.
 *
 * <p>The service keeps it to ONE row per operation (SPEC odluka 4); the table
 * takes one per attribute (AND-semantics) so relaxing that someday is a
 * validation change, not a migration. The attribute is denormalized for the
 * same composite-FK reason as in {@code product_variant_values}.
 */
@Entity
@Table(name = "operation_variant_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationVariantOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_attribute_id", nullable = false)
    private VariantAttribute attribute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_attribute_option_id", nullable = false)
    private VariantAttributeOption option;
}
