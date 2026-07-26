package com.aleksandarparipovic.marel_app.user_table_preferences;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A user's own table layouts. The user id is never part of the route. */
@RestController
@RequestMapping("/api/user-table-preferences")
@RequiredArgsConstructor
public class UserTablePreferenceController {

    private final UserTablePreferenceService service;
    private final CurrentUserService currentUserService;

    @GetMapping("/{tableKey}")
    public ResponseEntity<java.util.Map<String, Object>> get(@PathVariable String tableKey) {
        return ResponseEntity.ok(
                service.get(currentUserService.getCurrentUserId(), tableKey));
    }

    @PutMapping("/{tableKey}")
    public ResponseEntity<java.util.Map<String, Object>> save(
            @PathVariable String tableKey, @RequestBody java.util.Map<String, Object> settings
    ) {
        return ResponseEntity.ok(
                service.save(currentUserService.getCurrentUserId(), tableKey, settings));
    }

    @DeleteMapping("/{tableKey}")
    public ResponseEntity<Void> reset(@PathVariable String tableKey) {
        service.reset(currentUserService.getCurrentUserId(), tableKey);
        return ResponseEntity.noContent().build();
    }
}
