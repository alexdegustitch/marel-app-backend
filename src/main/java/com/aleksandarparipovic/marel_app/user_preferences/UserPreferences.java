package com.aleksandarparipovic.marel_app.user_preferences;

import com.aleksandarparipovic.marel_app.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One user's global application settings.
 *
 * <p>Keyed by user_id with no surrogate id, so a second row for the same user is
 * structurally impossible.
 *
 * <p>Anything the backend reads, validates or might query is a typed column.
 * {@link #uiSettings} is for visual-only extras the backend never interprets —
 * notably NOT the notification toggles, which the fan-out depends on.
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreferences {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    @Builder.Default
    private UserTheme theme = UserTheme.SYSTEM;

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = "sr";

    @Column(name = "timezone", nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Europe/Belgrade";

    @Column(name = "date_format", nullable = false, length = 32)
    @Builder.Default
    private String dateFormat = "dd.MM.yyyy";

    @Column(name = "time_format", nullable = false, length = 32)
    @Builder.Default
    private String timeFormat = "HH:mm";

    @Column(name = "number_format", nullable = false, length = 32)
    @Builder.Default
    private String numberFormat = "sr-RS";

    @Enumerated(EnumType.STRING)
    @Column(name = "ui_density", nullable = false, length = 20)
    @Builder.Default
    private UiDensity uiDensity = UiDensity.COMFORTABLE;

    @Column(name = "rows_per_page", nullable = false)
    @Builder.Default
    private Integer rowsPerPage = 25;

    @Column(name = "sidebar_collapsed", nullable = false)
    @Builder.Default
    private Boolean sidebarCollapsed = false;

    /** Read by the notification fan-out, which is why it is a column and not JSON. */
    @Column(name = "email_notifications_enabled", nullable = false)
    @Builder.Default
    private Boolean emailNotificationsEnabled = true;

    @Column(name = "in_app_notifications_enabled", nullable = false)
    @Builder.Default
    private Boolean inAppNotificationsEnabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_settings", columnDefinition = "jsonb", nullable = false)
    private JsonNode uiSettings;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
