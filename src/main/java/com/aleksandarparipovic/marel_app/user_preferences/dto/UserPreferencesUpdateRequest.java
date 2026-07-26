package com.aleksandarparipovic.marel_app.user_preferences.dto;

import com.aleksandarparipovic.marel_app.user_preferences.UiDensity;
import com.aleksandarparipovic.marel_app.user_preferences.UserTheme;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave unchanged" for every field. Enum-typed fields reject unknown
 * values at deserialization, so an invalid theme never reaches the database.
 */
@Getter
@Setter
public class UserPreferencesUpdateRequest {

    private UserTheme theme;

    @Size(max = 10)
    private String language;

    @Size(max = 64)
    private String timezone;

    @Size(max = 32)
    private String dateFormat;

    @Size(max = 32)
    private String timeFormat;

    @Size(max = 32)
    private String numberFormat;

    private UiDensity uiDensity;

    // Mirrors chk_user_preferences_rows_per_page.
    @Min(value = 5, message = "Minimalno 5 redova po strani")
    @Max(value = 500, message = "Maksimalno 500 redova po strani")
    private Integer rowsPerPage;

    private Boolean sidebarCollapsed;
    private Boolean emailNotificationsEnabled;
    private Boolean inAppNotificationsEnabled;

    /**
     * Plain Map, not a JsonNode: the HTTP layer is Jackson 3 and cannot bind the
     * Jackson 2 node type the entity uses. See JsonPayloads.
     * Structure and size are validated in the service.
     */
    private java.util.Map<String, Object> uiSettings;
}
