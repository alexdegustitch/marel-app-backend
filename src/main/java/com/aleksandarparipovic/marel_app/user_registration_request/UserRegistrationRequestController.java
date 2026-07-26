package com.aleksandarparipovic.marel_app.user_registration_request;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.user_registration_request.dto.RegistrationRequestResponse;
import com.aleksandarparipovic.marel_app.user_registration_request.dto.RegistrationReviewRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Administrator review of self-registrations.
 *
 * <p>Nothing here accepts a reviewer id, timestamp or target status from the
 * client: the reviewer comes from the security context, the timestamp from the
 * server clock, and the status from which endpoint was called. The only client
 * input is an optional note.
 */
@RestController
@RequestMapping("/api/registration-requests")
@RequiredArgsConstructor
public class UserRegistrationRequestController {

    /** Bounded so an "all history" call can never return an unbounded collection. */
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRegistrationRequestService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("@perm.has('USER_REGISTRATION_READ_ALL')")
    public ResponseEntity<Page<RegistrationRequestResponse>> list(
            @RequestParam(required = false) UserRegistrationRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return ResponseEntity.ok(service.list(
                status,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))
        ));
    }

    /** Badge count for the admin navigation. */
    @GetMapping("/pending-count")
    @PreAuthorize("@perm.has('USER_REGISTRATION_READ_ALL')")
    public ResponseEntity<Map<String, Long>> pendingCount() {
        return ResponseEntity.ok(Map.of("count", service.countPending()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('USER_REGISTRATION_READ_ALL')")
    public ResponseEntity<RegistrationRequestResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@perm.has('USER_REGISTRATION_APPROVE')")
    public ResponseEntity<RegistrationRequestResponse> approve(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid RegistrationReviewRequest request
    ) {
        return ResponseEntity.ok(service.approve(
                id, currentUserService.getCurrentUserId(), noteOf(request)));
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("@perm.has('USER_REGISTRATION_APPROVE')")
    public ResponseEntity<RegistrationRequestResponse> decline(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid RegistrationReviewRequest request
    ) {
        return ResponseEntity.ok(service.decline(
                id, currentUserService.getCurrentUserId(), noteOf(request)));
    }

    /**
     * Withdraw a request. The applicant may withdraw their own; a user with the
     * approval permission may withdraw anyone's. Horizontal authorization is
     * checked here rather than only vertically, so knowing an id is not enough to
     * cancel somebody else's registration.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<RegistrationRequestResponse> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid RegistrationReviewRequest request
    ) {
        Long actorId = currentUserService.getCurrentUserId();
        RegistrationRequestResponse existing = service.getById(id);

        boolean isOwner = existing.userId().equals(actorId);
        boolean mayReview = permissionService.hasPermission(AppPermission.USER_REGISTRATION_APPROVE);

        if (!isOwner && !mayReview) {
            throw new AccessDeniedException("Nije dozvoljeno otkazivanje tuđeg zahteva.");
        }

        return ResponseEntity.ok(service.cancel(id, actorId, noteOf(request)));
    }

    private static String noteOf(RegistrationReviewRequest request) {
        return request == null ? null : request.getReviewNote();
    }
}
