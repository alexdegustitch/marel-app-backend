package com.aleksandarparipovic.marel_app.production_order_recipient;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.production_order_recipient.dto.ManualRecipientRequest;
import com.aleksandarparipovic.marel_app.production_order_recipient.dto.ProductionOrderRecipientResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The recipient snapshot for one production order.
 *
 * <p>READING AND WRITING ARE TWO PERMISSIONS. The class requires
 * PRODUCTION_ORDER_RECIPIENT_VIEW, so nobody outside the order's audience gets
 * near it at all; every method that CHANGES who is told additionally requires
 * PRODUCTION_ORDER_RECIPIENT_MANAGE.
 *
 * <p>The split exists for the supervisor, who runs the order and must be able to
 * see who was informed about it, and who must not be able to add somebody to
 * that list or take somebody off it — the same read/write line the order itself
 * has.
 *
 * <p>The manual-add path carries the write permission like the rest: manual
 * entry must not become a way around production-order authorization.
 */
@RestController
@RequestMapping("/api/production-orders/{orderId}/recipients")
@RequiredArgsConstructor
@PreAuthorize("@perm.has('PRODUCTION_ORDER_RECIPIENT_VIEW')")
public class ProductionOrderRecipientController {

    private final ProductionOrderRecipientService service;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<List<ProductionOrderRecipientResponse>> list(@PathVariable Long orderId) {
        return ResponseEntity.ok(service.listRecipients(orderId));
    }

    /** Attaching a list snapshots its current members into the order. */
    @PostMapping("/mailing-lists/{mailingListId}")
    @PreAuthorize("@perm.has('PRODUCTION_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<List<ProductionOrderRecipientResponse>> attachMailingList(
            @PathVariable Long orderId, @PathVariable Long mailingListId
    ) {
        return ResponseEntity.ok(service.attachMailingList(
                orderId, mailingListId, currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/mailing-lists/{mailingListId}")
    @PreAuthorize("@perm.has('PRODUCTION_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<List<ProductionOrderRecipientResponse>> detachMailingList(
            @PathVariable Long orderId, @PathVariable Long mailingListId
    ) {
        return ResponseEntity.ok(service.detachMailingList(
                orderId, mailingListId, currentUserService.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("@perm.has('PRODUCTION_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<ProductionOrderRecipientResponse> addManual(
            @PathVariable Long orderId,
            @RequestBody @Valid ManualRecipientRequest request
    ) {
        return ResponseEntity.ok(service.addManualRecipient(
                orderId, request.getEmail(), request.getName(),
                currentUserService.getCurrentUserId()));
    }

    @DeleteMapping("/{recipientId}")
    @PreAuthorize("@perm.has('PRODUCTION_ORDER_RECIPIENT_MANAGE')")
    public ResponseEntity<Void> remove(
            @PathVariable Long orderId, @PathVariable Long recipientId
    ) {
        service.removeRecipient(orderId, recipientId, currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
