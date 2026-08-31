package com.aleksandarparipovic.marel_app.sample_order;

import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCardRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCopySourceRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCreateRequest;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderDetailDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderOptionDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Nalozi za izradu uzoraka.
 *
 * <p>No {@code @PreAuthorize} here: the read/write split is stated once in
 * {@code SecurityConfig}, by URL and method, exactly as it is for production
 * orders. Repeating it per method would be a second place for the rule to live
 * and a second place for it to drift.
 */
@RestController
@RequestMapping("api/sample-orders")
@RequiredArgsConstructor
public class SampleOrderController {

    private final SampleOrderService sampleOrderService;

    @PostMapping
    ResponseEntity<SampleOrderDetailDto> create(@Valid @RequestBody SampleOrderCreateRequest request) {
        return ResponseEntity.ok(sampleOrderService.create(request));
    }

    @GetMapping("/active-sample-orders")
    ResponseEntity<List<SampleOrderOptionDto>> getAllActiveSampleOrders() {
        return ResponseEntity.ok(sampleOrderService.getAllActiveSampleOrders());
    }

    @PostMapping("/search-all")
    ResponseEntity<Page<SampleOrderCardRow>> searchAll(@RequestBody SearchRequest request) {
        return ResponseEntity.ok(sampleOrderService.searchAll(request));
    }

    /**
     * Past orders to copy line items out of — what "Kopiraj stavku" reads.
     *
     * <p>Searched, filtered, sorted and paged BY THE SERVER. {@code query} is one
     * box over everything an order is recognised by: its code, name and note, its
     * customer's name, code and tax id, and on its lines the product's name and
     * code, the description for the shop floor, the notes, and the quantity read
     * as text.
     *
     * <p>The three filters answer "which one was it" when the box cannot —
     * {@code userId} who wrote it, {@code customerId} who it was for, and
     * {@code createdFrom}/{@code createdTo} when it was created. The date span is
     * inclusive at both ends, so one day given twice means that day.
     *
     * <p>Written as a GET with plain parameters rather than through the shared
     * {@code SearchRequest}: this is a fixed question with five answers, and a
     * generic filter list would let a caller sort or filter by fields this screen
     * has no business reaching.
     */
    @GetMapping("/copy-sources")
    ResponseEntity<Page<SampleOrderCopySourceRow>> copySources(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(sampleOrderService.searchCopySources(
                query, customerId, userId, createdFrom, createdTo, page, size));
    }

    @GetMapping("/{id}")
    ResponseEntity<SampleOrderDetailDto> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(sampleOrderService.getDetail(id));
    }

    @PutMapping("/{id}")
    ResponseEntity<SampleOrderDetailDto> update(
            @PathVariable Long id, @Valid @RequestBody SampleOrderUpdateRequest request
    ) {
        return ResponseEntity.ok(sampleOrderService.update(id, request));
    }

    @PatchMapping("/{id}/close")
    ResponseEntity<SampleOrderDetailDto> close(@PathVariable Long id) {
        return ResponseEntity.ok(sampleOrderService.close(id));
    }
}
