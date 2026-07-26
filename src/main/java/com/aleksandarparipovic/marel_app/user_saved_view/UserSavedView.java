package com.aleksandarparipovic.marel_app.user_saved_view;

import com.aleksandarparipovic.marel_app.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A named, reusable filter/sort/column configuration owned by one user.
 *
 * <p>A saved view is data, never behaviour: the JSON describes WHICH validated
 * fields to filter on and WHAT values to bind. It never contains an SQL fragment,
 * and its values are always bound as parameters. A saved view can therefore never
 * widen what its owner is allowed to see.
 */
@Entity
@Table(name = "user_saved_views")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "view_key", nullable = false, length = 80, updatable = false)
    private String viewKey;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filters", columnDefinition = "jsonb", nullable = false)
    private JsonNode filters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sorting", columnDefinition = "jsonb", nullable = false)
    private JsonNode sorting;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb", nullable = false)
    private JsonNode columns;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
