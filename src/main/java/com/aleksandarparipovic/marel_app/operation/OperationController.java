package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.operation.dto.*;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.SearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationService operationService;
    private final OperationDetailService operationDetailService;

    @PostMapping("/search-all")
    public Page<OperationWithProductInfoRow> searchAll(@RequestBody SearchRequest searchRequest){
        return operationService.searchAll(searchRequest);
    }

    @GetMapping("/active-operations-for-product/id={id}&date={date}")
    public ResponseEntity<List<OperationBasicInfoDto>> getAllOperationsForProduct(@PathVariable Long id, @PathVariable LocalDate date){
        List<OperationBasicInfoDto> operationBasicInfoDtos = operationService.getAllOperationsForProduct(id, date);
        return ResponseEntity.ok(operationBasicInfoDtos);
    }

    @GetMapping("/operations-for-product/id={id}&date={date}")
    public ResponseEntity<List<OperationDto>> getAllOperationsForProductDto(@PathVariable Long id, @PathVariable LocalDate date){
        return ResponseEntity.ok(operationService.getAllOperationsForProductDto(id, date));
    }

    /**
     * The operations a full-day absence is drawn with, by category CODE.
     *
     * <p>Declared before {@code /{id}} so the literal path is not read as an id.
     * Spring prefers the exact match either way; the order says so to the reader.
     */
    @GetMapping("/by-category")
    public ResponseEntity<List<AbsenceOperationDto>> getOperationsByCategoryNo(
            @RequestParam String categoryNo
    ){
        return ResponseEntity.ok(operationService.getActiveOperationsByCategoryNo(categoryNo));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationWithProductNameDto> getOperation(@PathVariable Long id){
        OperationWithProductNameDto operation= operationService.getOperation(id);
        return ResponseEntity.ok(operation);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OperationWithProductInfoRow> updateOperation(@PathVariable Long id, @RequestBody @Valid OperationUpdateRequest request){
        OperationWithProductInfoRow operation = operationService.updateOperation(id,   request);
        return ResponseEntity.ok(operation);
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<Void> archiveOperation(
            @PathVariable Long id,
            @RequestBody OperationArchiveRequest request,
            Authentication authentication
    ) {
        operationService.archiveOperation(id, request.password(), request.reason(), authentication);
        return ResponseEntity.noContent().build();
    }

    /** What stands in the way of archiving — empty list means it may be archived. */
    @GetMapping("/{id}/archive-blockers")
    public ResponseEntity<List<String>> getArchiveBlockers(@PathVariable Long id) {
        return ResponseEntity.ok(operationDetailService.getArchiveBlockers(id));
    }

    @PatchMapping("/{id}/name")
    public ResponseEntity<Void> renameOperation(
            @PathVariable Long id,
            @RequestBody @Valid OperationRenameRequest request
    ) {
        operationService.renameOperation(id, request.operationName());
        return ResponseEntity.noContent().build();
    }

    // ── The operation detail page ───────────────────────────────────────────

    /**
     * The norm history. Archived norms are left out unless asked for — they are
     * still history, so the screen offers them behind a switch.
     */
    @GetMapping("/{id}/norm-versions")
    public ResponseEntity<List<OperationNormVersionDto>> getNormHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        return ResponseEntity.ok(operationDetailService.getNormHistory(id, includeArchived));
    }

    /** Which norm the operation worked to, and since when — the whole chronology. */
    @GetMapping("/{id}/norm-activations")
    public ResponseEntity<List<OperationNormActivationDto>> getNormActivations(@PathVariable Long id) {
        return ResponseEntity.ok(operationDetailService.getNormActivations(id));
    }

    @PostMapping("/{id}/norm-versions")
    public ResponseEntity<OperationNormVersionDto> addNorm(
            @PathVariable Long id,
            @RequestBody OperationNormVersionCreateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(operationDetailService.addNorm(id, request, authentication));
    }

    /** Edits the norm in force. Older versions are history and stay untouched. */
    @PutMapping("/{id}/norm-versions/{versionId}")
    public ResponseEntity<OperationNormVersionDto> updateNorm(
            @PathVariable Long id,
            @PathVariable Long versionId,
            @RequestBody OperationNormVersionCreateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(operationDetailService.updateNorm(id, versionId, request, authentication));
    }

    /**
     * Puts a norm from the history back in force — an archived one included,
     * which un-archives it.
     */
    @PostMapping("/{id}/norm-versions/{versionId}/activate")
    public ResponseEntity<OperationNormVersionDto> activateNorm(
            @PathVariable Long id,
            @PathVariable Long versionId,
            @RequestBody(required = false) OperationNormActivationRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(operationDetailService.activateNorm(
                id, versionId, request == null ? null : request.reason(), authentication));
    }

    /** Archives the norm in force; the one that applies next takes over. */
    @DeleteMapping("/{id}/norm-versions/{versionId}")
    public ResponseEntity<Void> archiveNorm(
            @PathVariable Long id,
            @PathVariable Long versionId,
            Authentication authentication
    ) {
        operationDetailService.archiveNorm(id, versionId, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/norm-versions/{versionId}/verify")
    public ResponseEntity<OperationNormVersionDto> verifyNorm(
            @PathVariable Long id,
            @PathVariable Long versionId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(operationDetailService.verifyNorm(id, versionId, authentication));
    }

    @GetMapping("/{id}/production-orders")
    public ResponseEntity<List<OperationOrderUsageRow>> getProductionOrders(@PathVariable Long id) {
        return ResponseEntity.ok(operationDetailService.getProductionOrders(id));
    }

    @GetMapping("/{id}/sample-orders")
    public ResponseEntity<List<ProductSampleOrderRow>> getSampleOrders(@PathVariable Long id) {
        return ResponseEntity.ok(operationDetailService.getSampleOrders(id));
    }

    @GetMapping("/{id}/work-logs")
    public ResponseEntity<List<OperationWorkLogRow>> getRecentWorkLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(operationDetailService.getRecentWorkLogs(id, limit));
    }

    @GetMapping("/{id}/output")
    public ResponseEntity<List<OperationOutputPointDto>> getMonthlyOutput(
            @PathVariable Long id,
            @RequestParam(defaultValue = "12") int months
    ) {
        return ResponseEntity.ok(operationDetailService.getMonthlyOutput(id, months));
    }


    @PostMapping
    public ResponseEntity<OperationWithProductInfoRow> create(@RequestBody @Valid OperationCreateRequest request){
        OperationWithProductInfoRow created = operationService.create(request);
        URI location = URI.create("/operations/" + created.getOperationId());

        return ResponseEntity
                .created(location)
                .body(created);
    }
}
