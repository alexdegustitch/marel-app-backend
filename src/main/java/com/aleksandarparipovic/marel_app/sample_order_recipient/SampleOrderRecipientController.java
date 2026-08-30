package com.aleksandarparipovic.marel_app.sample_order_recipient;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.sample_order_recipient.dto.SampleOrderManualRecipientRequest;
import com.aleksandarparipovic.marel_app.sample_order_recipient.dto.SampleOrderRecipientResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The recipient snapshot for one sample order.
 *
 * <p>READING AND WRITING ARE TWO PERMISSIONS, the same split the production
 * order's recipients have. The class requires SAMPLE_ORDER_RECIPIENT_VIEW, so
 * nobody outside the order's audience gets near it at all; every method that
 * CHANGES who is told additionally requires SAMPLE_ORDER_RECIPIENT_MANAGE.
 *
 * <p>The manual-add path carries the write permission like the rest: manual
 * entry must not become a way around sample-order authorization.
 */
@RestController
@RequestMapping("/api/sample-orders/{orderId}/recipients")
@RequiredArgsConstructor
@PreAuthorize("@perm.has('SAMPLE_ORDER_RECIPIENT_VIEW')")
public class SampleOrderRecipientController {

    private final SampleOrderRecipientService service;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<List<SampleOrderRecipientResponse>> list(@PathVariable Long orderId) {
        return ResponseEntity.ok(service.listRecipients(orderId));
    }

    /** Attaching a list snapshots its current members into the order. */
    @PostMapping("/mailing-lists/{mailingListId}")
    @PreAuthorize("@perm.has('SAMPLE_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<List<SampleOrderRecipientResponse>> attachMailingList(
            @PathVariable Long orderId, @PathVariable Long mailingListId
    ) {
        return ResponseEntity.ok(service.attachMailingList(
                orderId, mailingListId, currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/mailing-lists/{mailingListId}")
    @PreAuthorize("@perm.has('SAMPLE_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<List<SampleOrderRecipientResponse>> detachMailingList(
            @PathVariable Long orderId, @PathVariable Long mailingListId
    ) {
        return ResponseEntity.ok(service.detachMailingList(
                orderId, mailingListId, currentUserService.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("@perm.has('SAMPLE_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<SampleOrderRecipientResponse> addManual(
            @PathVariable Long orderId,
            @RequestBody @Valid SampleOrderManualRecipientRequest request
    ) {
        return ResponseEntity.ok(service.addManualRecipient(
                orderId, request.getEmail(), request.getName(),
                currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/{recipientId}")
    @PreAuthorize("@perm.has('SAMPLE_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<Void> remove(
            @PathVariable Long orderId, @PathVariable Long recipientId
    ) {
        service.removeRecipient(orderId, recipientId, currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
