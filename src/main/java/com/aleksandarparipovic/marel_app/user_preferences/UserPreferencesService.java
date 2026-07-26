package com.aleksandarparipovic.marel_app.user_preferences;

import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_preferences.dto.UserPreferencesResponse;
import com.aleksandarparipovic.marel_app.user_preferences.dto.UserPreferencesUpdateRequest;
import com.aleksandarparipovic.marel_app.common.JsonPayloads;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * A user's global preferences.
 *
 * <p>Created lazily on first read rather than backfilled for every existing user
 * or wired into user creation — no row is a valid state meaning "all defaults".
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    /** Mirrors chk_user_preferences_ui_settings; rejected here with a readable message. */
    private static final int MAX_UI_SETTINGS_BYTES = 16 * 1024;

    private final UserPreferencesRepository preferencesRepository;
    private final UserRepository userRepository;

    @Transactional
    public UserPreferences getOrCreateForUser(Long userId) {
        return preferencesRepository.findById(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new EntityNotFoundException(
                                    "Korisnik nije pronađen: " + userId));
                    return preferencesRepository.save(UserPreferences.builder()
                            .user(user)
                            .uiSettings(JsonPayloads.emptyObject())
                            .build());
                });
    }

    @Transactional
    public UserPreferencesResponse get(Long userId) {
        return toResponse(getOrCreateForUser(userId));
    }

    /**
     * Applies only known fields. Anything else in the request body is ignored
     * rather than persisted, so a client cannot invent preferences.
     */
    @Transactional
    public UserPreferencesResponse update(Long userId, UserPreferencesUpdateRequest request) {
        UserPreferences preferences = getOrCreateForUser(userId);

        if (request.getTheme() != null) {
            preferences.setTheme(request.getTheme());
        }
        if (request.getLanguage() != null) {
            preferences.setLanguage(request.getLanguage().trim());
        }
        if (request.getTimezone() != null) {
            preferences.setTimezone(request.getTimezone().trim());
        }
        if (request.getDateFormat() != null) {
            preferences.setDateFormat(request.getDateFormat().trim());
        }
        if (request.getTimeFormat() != null) {
            preferences.setTimeFormat(request.getTimeFormat().trim());
        }
        if (request.getNumberFormat() != null) {
            preferences.setNumberFormat(request.getNumberFormat().trim());
        }
        if (request.getUiDensity() != null) {
            preferences.setUiDensity(request.getUiDensity());
        }
        if (request.getRowsPerPage() != null) {
            preferences.setRowsPerPage(request.getRowsPerPage());
        }
        if (request.getSidebarCollapsed() != null) {
            preferences.setSidebarCollapsed(request.getSidebarCollapsed());
        }
        if (request.getEmailNotificationsEnabled() != null) {
            preferences.setEmailNotificationsEnabled(request.getEmailNotificationsEnabled());
        }
        if (request.getInAppNotificationsEnabled() != null) {
            preferences.setInAppNotificationsEnabled(request.getInAppNotificationsEnabled());
        }
        if (request.getUiSettings() != null) {
            preferences.setUiSettings(validateUiSettings(request.getUiSettings()));
        }

        return toResponse(preferences);
    }

    /**
     * Read by the notification fan-out. Defaults to enabled when no row exists yet,
     * so a user who has never opened settings still gets told about things.
     */
    @Transactional(readOnly = true)
    public boolean inAppNotificationsEnabled(Long userId) {
        return preferencesRepository.findById(userId)
                .map(UserPreferences::getInAppNotificationsEnabled)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean emailNotificationsEnabled(Long userId) {
        return preferencesRepository.findById(userId)
                .map(UserPreferences::getEmailNotificationsEnabled)
                .orElse(true);
    }

    /**
     * ui_settings must be a JSON object of bounded size — never an array, scalar or
     * unbounded blob. Checked here so the caller gets a 400 with an explanation
     * rather than a raw constraint violation.
     */
    private static JsonNode validateUiSettings(java.util.Map<String, Object> uiSettings) {
        JsonNode node = JsonPayloads.toNode(uiSettings);
        if (!node.isObject()) {
            throw new IllegalArgumentException("ui_settings mora da bude JSON objekat.");
        }
        if (JsonPayloads.byteSize(node) > MAX_UI_SETTINGS_BYTES) {
            throw new IllegalArgumentException(
                    "ui_settings je prevelik (maksimum " + MAX_UI_SETTINGS_BYTES + " bajtova).");
        }
        return node;
    }

    private UserPreferencesResponse toResponse(UserPreferences p) {
        return new UserPreferencesResponse(
                p.getUserId(),
                p.getTheme(),
                p.getLanguage(),
                p.getTimezone(),
                p.getDateFormat(),
                p.getTimeFormat(),
                p.getNumberFormat(),
                p.getUiDensity(),
                p.getRowsPerPage(),
                p.getSidebarCollapsed(),
                p.getEmailNotificationsEnabled(),
                p.getInAppNotificationsEnabled(),
                JsonPayloads.toMap(p.getUiSettings())
        );
    }
}
