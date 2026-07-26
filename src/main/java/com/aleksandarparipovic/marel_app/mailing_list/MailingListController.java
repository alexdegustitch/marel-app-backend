package com.aleksandarparipovic.marel_app.mailing_list;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.mailing_list.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-managed mailing lists.
 *
 * <p>Every operation resolves the actor from the security context and re-checks
 * access against the loaded list, so an id alone never grants reach.
 */
@RestController
@RequestMapping("/api/mailing-lists")
@RequiredArgsConstructor
public class MailingListController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MailingListService service;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<Page<MailingListResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return ResponseEntity.ok(service.listAccessible(
                currentUserService.getCurrentUserId(),
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.ASC, "name"))
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MailingListResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id, currentUserService.getCurrentUserId()));
    }

    @PostMapping
    public ResponseEntity<MailingListResponse> create(
            @RequestBody @Valid MailingListCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(request, currentUserService.getCurrentUserId()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<MailingListResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid MailingListUpdateRequest request
    ) {
        return ResponseEntity.ok(
                service.update(id, request, currentUserService.getCurrentUserId()));
    }

    /** Archive, not delete — production-order history references this list. */
    @PostMapping("/{id}/archive")
    public ResponseEntity<MailingListResponse> archive(@PathVariable Long id) {
        return ResponseEntity.ok(service.archive(id, currentUserService.getCurrentUserId()));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<MailingListMemberResponse>> members(@PathVariable Long id) {
        return ResponseEntity.ok(service.listMembers(id, currentUserService.getCurrentUserId()));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<MailingListMemberResponse> addMember(
            @PathVariable Long id,
            @RequestBody @Valid MailingListMemberCreateRequest request
    ) {
        return ResponseEntity.ok(
                service.addMember(id, request, currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long id, @PathVariable Long memberId
    ) {
        service.removeMember(id, memberId, currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/access")
    public ResponseEntity<Void> grantAccess(
            @PathVariable Long id,
            @RequestBody @Valid MailingListAccessRequest request
    ) {
        service.grantAccess(id, request.getUserId(), currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/access/{userId}")
    public ResponseEntity<Void> revokeAccess(
            @PathVariable Long id, @PathVariable Long userId
    ) {
        service.revokeAccess(id, userId, currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
