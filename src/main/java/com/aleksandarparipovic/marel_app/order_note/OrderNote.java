package com.aleksandarparipovic.marel_app.order_note;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One public note left on an order. Exactly one of {@link #productionOrder} and
 * {@link #sampleOrder} is set — the CHECK in V56 enforces it — so a note always
 * belongs to one order of one kind. {@link #bodyJson} is the editor's document
 * verbatim; {@link #bodyText} is the same note flattened to plain text on write,
 * and is what search and sort read.
 */
@Entity
@Table(name = "order_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Set for a note on a production order; null for a note on a sample order. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    /** Set for a note on a sample order; null for a note on a production order. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sample_order_id")
    private SampleOrder sampleOrder;

    /** Who wrote it — the signature the wall is read by, never null. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    /** The rich-text document (TipTap JSON), held as jsonb so it reads back exactly as written. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body_json", columnDefinition = "jsonb", nullable = false)
    private JsonNode bodyJson;

    /** The note flattened to plain text on write — what search, sort and the preview read. */
    @Column(name = "body_text", nullable = false)
    private String bodyText = "";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // DB-managed timestamps. @Generated makes Hibernate SELECT them back after the
    // write, so a freshly created or edited note carries its real created_at /
    // updated_at in the response rather than a null the screen would misread.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
