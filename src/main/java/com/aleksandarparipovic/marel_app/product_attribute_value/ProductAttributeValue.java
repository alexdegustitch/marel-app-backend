package com.aleksandarparipovic.marel_app.product_attribute_value;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product_type_attribute.ProductTypeAttribute;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One product's value for one attribute of its type — a single cell of the
 * catalogue's spec table (e.g. product "CuCPST 10.6", attribute "L", value "24.5").
 *
 * <p><b>The invariant SQL cannot state:</b> the attribute must belong to the
 * product's type. A composite foreign key cannot express it because a product's
 * type is nullable, so it is enforced in {@link ProductAttributeValueService} and
 * covered by a test. The unique index (product, attribute) still guarantees a
 * product holds at most one value per attribute.
 *
 * <p>The value is stored as text and parsed according to the attribute's
 * data type; "16-95" and "2/1" are values a number column could not hold.
 */
@Entity
@Table(name = "product_attribute_values")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductAttributeValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_attribute_id", nullable = false)
    private ProductTypeAttribute attribute;

    @Column(name = "value", length = 255)
    private String value;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
