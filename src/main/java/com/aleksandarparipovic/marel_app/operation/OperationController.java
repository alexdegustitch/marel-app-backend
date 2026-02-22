package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationUpdateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.SearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationService operationService;

    @PostMapping("/search-all")
    public Page<OperationWithProductInfoRow> searchAll(@RequestBody SearchRequest searchRequest){
        return operationService.searchAll(searchRequest);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationDto> getOperation(@PathVariable Long id){
        OperationDto operationDto = operationService.getOperation(id);
        return ResponseEntity.ok(operationDto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OperationWithProductInfoRow> updateOperation(@PathVariable Long id, @RequestBody @Valid OperationUpdateRequest request){
        OperationWithProductInfoRow operation = operationService.updateOperation(id,   request);
        return ResponseEntity.ok(operation);
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
