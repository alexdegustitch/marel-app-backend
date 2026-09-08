package com.aleksandarparipovic.marel_app.image;

import com.aleksandarparipovic.marel_app.file.FileMetadata;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One image attached to a family, a type or a product.
 *
 * <p>The thin link between an owner and a stored {@link FileMetadata}: it says
 * whose the image is, whether it is the primary one, and in what order it sits.
 * Exactly one of the three owner references is set (a database CHECK enforces
 * it), and a partial unique index allows at most one primary per owner.
 *
 * <p>The bytes are not here — {@code file} points at the row that holds the
 * storage key. Deleting the file cascades the link away.
 */
@Entity
@Table(name = "images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_family_id")
    private ProductFamily productFamily;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id")
    private ProductType productType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileMetadata file;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
