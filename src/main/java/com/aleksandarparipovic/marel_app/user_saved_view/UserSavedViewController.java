package com.aleksandarparipovic.marel_app.user_saved_view;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.user_saved_view.dto.SavedViewRequest;
import com.aleksandarparipovic.marel_app.user_saved_view.dto.SavedViewResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A user's own saved views. Ownership is re-checked on every mutating call. */
@RestController
@RequestMapping("/api/saved-views")
@RequiredArgsConstructor
public class UserSavedViewController {

    private final UserSavedViewService service;
    private final CurrentUserService currentUserService;

    @GetMapping("/{viewKey}")
    public ResponseEntity<List<SavedViewResponse>> list(@PathVariable String viewKey) {
        return ResponseEntity.ok(
                service.list(currentUserService.getCurrentUserId(), viewKey));
    }

    @PostMapping("/{viewKey}")
    public ResponseEntity<SavedViewResponse> create(
            @PathVariable String viewKey,
            @RequestBody @Valid SavedViewRequest request
    ) {
        return ResponseEntity.ok(
                service.create(currentUserService.getCurrentUserId(), viewKey, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SavedViewResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid SavedViewRequest request
    ) {
        return ResponseEntity.ok(
                service.update(id, currentUserService.getCurrentUserId(), request));
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<SavedViewResponse> setDefault(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.setDefault(id, currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id, currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
