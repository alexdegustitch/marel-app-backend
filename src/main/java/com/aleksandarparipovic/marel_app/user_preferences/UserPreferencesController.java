package com.aleksandarparipovic.marel_app.user_preferences;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.user_preferences.dto.UserPreferencesResponse;
import com.aleksandarparipovic.marel_app.user_preferences.dto.UserPreferencesUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * A user's own preferences.
 *
 * <p>The routes are deliberately "/me" only — the user id comes from the security
 * context, so there is no id in the path a caller could change to read somebody
 * else's settings. Administrative access is a separate, permission-gated route.
 */
@RestController
@RequestMapping("/api/user-preferences")
@RequiredArgsConstructor
public class UserPreferencesController {

    private final UserPreferencesService service;
    private final CurrentUserService currentUserService;

    @GetMapping("/me")
    public ResponseEntity<UserPreferencesResponse> getMine() {
        return ResponseEntity.ok(service.get(currentUserService.getCurrentUserId()));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserPreferencesResponse> updateMine(
            @RequestBody @Valid UserPreferencesUpdateRequest request
    ) {
        return ResponseEntity.ok(
                service.update(currentUserService.getCurrentUserId(), request));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@perm.has('USER_PREFERENCES_ADMIN')")
    public ResponseEntity<UserPreferencesResponse> getForUser(@PathVariable Long userId) {
        return ResponseEntity.ok(service.get(userId));
    }
}
