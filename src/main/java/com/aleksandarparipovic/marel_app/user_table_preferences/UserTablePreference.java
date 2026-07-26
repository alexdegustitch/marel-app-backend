package com.aleksandarparipovic.marel_app.user_table_preferences;

import com.aleksandarparipovic.marel_app.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One user's column/sort/width layout for one dense table.
 *
 * <p>Purely presentational. These settings never affect authorization or backend
 * filtering: hiding a column is a display choice, and the backend decides what a
 * user may see independently of it.
 */
@Entity
@Table(name = "user_table_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTablePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * Validated against {@link TableKey}. A display key only — never interpolated
     * into SQL as an identifier.
     */
    @Column(name = "table_key", nullable = false, length = 80, updatable = false)
    private String tableKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
    private JsonNode settings;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
