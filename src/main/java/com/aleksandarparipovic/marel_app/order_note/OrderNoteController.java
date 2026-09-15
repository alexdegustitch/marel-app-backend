package com.aleksandarparipovic.marel_app.order_note;

import com.aleksandarparipovic.marel_app.order_note.dto.OrderNoteDto;
import com.aleksandarparipovic.marel_app.order_note.dto.OrderNoteRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The wall's HTTP face, for both kinds of order. Every route is nested under its
 * order — {@code /api/production-orders/{id}/notes} and
 * {@code /api/sample-orders/{id}/notes} — and SecurityConfig carves those two
 * sub-paths out as {@code authenticated()}, ABOVE the order rules that would
 * otherwise demand PRODUCTION_ORDER_MANAGE for a POST. So everyone signed in may
 * write; who may edit or delete a given note is the service's call.
 *
 * <p>Listing is paged, sorted and searched by the server: {@code q} over the
 * note text, {@code authorId} to one colleague, {@code direction} for newest- or
 * oldest-first. Nothing is filtered client-side.
 */
@RestController
@RequiredArgsConstructor
public class OrderNoteController {

    /** The largest page the wall will hand back at once. */
    private static final int MAX_SIZE = 100;

    private final OrderNoteService orderNoteService;

    // ── production orders ──────────────────────────────────────────────────────

    @GetMapping("/api/production-orders/{orderId}/notes")
    ResponseEntity<Page<OrderNoteDto>> listProduction(
            @PathVariable Long orderId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        return ResponseEntity.ok(orderNoteService.list(
                OrderNoteParentType.PRODUCTION, orderId, q, authorId, pageable(page, size, direction)));
    }

    @GetMapping("/api/production-orders/{orderId}/notes/count")
    ResponseEntity<Map<String, Long>> countProduction(@PathVariable Long orderId) {
        return ResponseEntity.ok(Map.of("count", orderNoteService.count(OrderNoteParentType.PRODUCTION, orderId)));
    }

    @PostMapping("/api/production-orders/{orderId}/notes")
    ResponseEntity<OrderNoteDto> createProduction(
            @PathVariable Long orderId, @Valid @RequestBody OrderNoteRequest request
    ) {
        return ResponseEntity.ok(orderNoteService.create(OrderNoteParentType.PRODUCTION, orderId, request));
    }

    @PutMapping("/api/production-orders/{orderId}/notes/{noteId}")
    ResponseEntity<OrderNoteDto> updateProduction(
            @PathVariable Long orderId, @PathVariable Long noteId, @Valid @RequestBody OrderNoteRequest request
    ) {
        return ResponseEntity.ok(orderNoteService.update(OrderNoteParentType.PRODUCTION, orderId, noteId, request));
    }

    @DeleteMapping("/api/production-orders/{orderId}/notes/{noteId}")
    ResponseEntity<Void> deleteProduction(@PathVariable Long orderId, @PathVariable Long noteId) {
        orderNoteService.delete(OrderNoteParentType.PRODUCTION, orderId, noteId);
        return ResponseEntity.noContent().build();
    }

    // ── sample orders ──────────────────────────────────────────────────────────

    @GetMapping("/api/sample-orders/{orderId}/notes")
    ResponseEntity<Page<OrderNoteDto>> listSample(
            @PathVariable Long orderId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        return ResponseEntity.ok(orderNoteService.list(
                OrderNoteParentType.SAMPLE, orderId, q, authorId, pageable(page, size, direction)));
    }

    @GetMapping("/api/sample-orders/{orderId}/notes/count")
    ResponseEntity<Map<String, Long>> countSample(@PathVariable Long orderId) {
        return ResponseEntity.ok(Map.of("count", orderNoteService.count(OrderNoteParentType.SAMPLE, orderId)));
    }

    @PostMapping("/api/sample-orders/{orderId}/notes")
    ResponseEntity<OrderNoteDto> createSample(
            @PathVariable Long orderId, @Valid @RequestBody OrderNoteRequest request
    ) {
        return ResponseEntity.ok(orderNoteService.create(OrderNoteParentType.SAMPLE, orderId, request));
    }

    @PutMapping("/api/sample-orders/{orderId}/notes/{noteId}")
    ResponseEntity<OrderNoteDto> updateSample(
            @PathVariable Long orderId, @PathVariable Long noteId, @Valid @RequestBody OrderNoteRequest request
    ) {
        return ResponseEntity.ok(orderNoteService.update(OrderNoteParentType.SAMPLE, orderId, noteId, request));
    }

    @DeleteMapping("/api/sample-orders/{orderId}/notes/{noteId}")
    ResponseEntity<Void> deleteSample(@PathVariable Long orderId, @PathVariable Long noteId) {
        orderNoteService.delete(OrderNoteParentType.SAMPLE, orderId, noteId);
        return ResponseEntity.noContent().build();
    }

    // ── shared ─────────────────────────────────────────────────────────────────

    /**
     * The page window and order the wall reads, sorted only ever by when a note
     * was written — newest first by default, oldest first when a reader asks.
     * Size is capped so a caller cannot ask for the whole table at once.
     */
    private static Pageable pageable(int page, int size, String direction) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_SIZE);
        Sort.Direction dir = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return PageRequest.of(safePage, safeSize, Sort.by(dir, "createdAt"));
    }
}
