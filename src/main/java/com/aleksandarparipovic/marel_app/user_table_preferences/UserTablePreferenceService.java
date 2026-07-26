package com.aleksandarparipovic.marel_app.user_table_preferences;

import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.common.JsonPayloads;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Per-user, per-table layout settings.
 *
 * <p>One row per (user, table_key), enforced by a unique index. Everything is
 * scoped to the calling user — there is no route or method that reaches another
 * user's layout.
 */
@Service
@RequiredArgsConstructor
public class UserTablePreferenceService {

    /** Mirrors chk_user_table_preferences_settings. */
    private static final int MAX_SETTINGS_BYTES = 32 * 1024;

    private final UserTablePreferenceRepository repository;
    private final UserRepository userRepository;

    /** Absent settings are a valid state meaning "table defaults". */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> get(Long userId, String rawTableKey) {
        String tableKey = TableKey.fromKey(rawTableKey).getKey();

        return repository.findByUser_IdAndTableKey(userId, tableKey)
                .map(preference -> JsonPayloads.toMap(preference.getSettings()))
                .orElseGet(java.util.Map::of);
    }

    @Transactional
    public java.util.Map<String, Object> save(
            Long userId, String rawTableKey, java.util.Map<String, Object> settings) {
        String tableKey = TableKey.fromKey(rawTableKey).getKey();
        JsonNode validated = validate(settings);

        UserTablePreference preference = repository
                .findByUser_IdAndTableKey(userId, tableKey)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new EntityNotFoundException(
                                    "Korisnik nije pronađen: " + userId));
                    return repository.save(UserTablePreference.builder()
                            .user(user)
                            .tableKey(tableKey)
                            .settings(JsonPayloads.emptyObject())
                            .build());
                });

        preference.setSettings(validated);
        return JsonPayloads.toMap(validated);
    }

    @Transactional
    public void reset(Long userId, String rawTableKey) {
        String tableKey = TableKey.fromKey(rawTableKey).getKey();
        repository.findByUser_IdAndTableKey(userId, tableKey).ifPresent(repository::delete);
    }

    /**
     * Settings must be a bounded JSON object.
     *
     * <p>Unknown keys are kept rather than rejected: the frontend owns the layout
     * vocabulary and adds to it faster than the backend, and rejecting an unknown
     * key would break a newer client against an older server. The backend never
     * interprets these values, so accepting them is safe — what matters is that the
     * shape and size are bounded, and that table_key is a closed set.
     */
    private static JsonNode validate(java.util.Map<String, Object> settings) {
        if (settings == null) {
            return JsonPayloads.emptyObject();
        }
        JsonNode node = JsonPayloads.toNode(settings);
        if (!node.isObject()) {
            throw new IllegalArgumentException("Podešavanja tabele moraju da budu JSON objekat.");
        }

        if (JsonPayloads.byteSize(node) > MAX_SETTINGS_BYTES) {
            throw new IllegalArgumentException(
                    "Podešavanja tabele su prevelika (maksimum " + MAX_SETTINGS_BYTES + " bajtova).");
        }

        return node;
    }
}
