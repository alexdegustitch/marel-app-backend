package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationUpdateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.operation.specification.OperationSpecifications;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepository;
    private final ProductRepository productRepository;
    private final OperationMapper operationMapper;

    public Page<OperationWithProductInfoRow> searchAll(SearchRequest request){
        Specification<Operation> spec = OperationSpecifications.fromSearchRequest(request);
        Pageable pageable = PageableBuilder.from(request);
        return operationRepository.searchWithProjection(spec, pageable, OperationWithProductInfoRow.class);
    }

    @Transactional(readOnly = true)
    public OperationDto getOperation(Long id){
        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operation not found"));
        return operationMapper.toDto(operation);
    }

    @Transactional
    public OperationWithProductInfoRow updateOperation(Long id, OperationUpdateRequest request){

        Operation operation = operationRepository.findById(id)
                .orElseThrow(()-> new IllegalArgumentException("Operation not found"));

        operation.setOpName(request.getOperationName());
        operation.setMinNorm(request.getMinNorm());
        operation.setMaxNorm(request.getMaxNorm());
        operation.setUnitsPerProduct(request.getUnitsPerProduct());
        operation.setNormDate(request.getNormDate());

        return operationRepository.findOperationWithProductById(id)
                .orElseThrow(()-> new EntityNotFoundException("Operation with product info not found"));
    }

    @Transactional
    public OperationWithProductInfoRow create(OperationCreateRequest request){
        Operation operation = new Operation();
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(()-> new EntityNotFoundException("Product not found"));

        operation.setProduct(product);
        operation.setOpName(request.getOperationName());
        operation.setMinNorm(request.getMinNorm());
        operation.setMaxNorm(request.getMaxNorm());
        operation.setUnitsPerProduct(request.getUnitsPerProduct());
        operation.setNormDate(request.getNormDate());
        operation = operationRepository.save(operation);
        return operationRepository.findOperationWithProductById(operation.getId())
                .orElseThrow(()-> new EntityNotFoundException("Operation with product info not found"));
    }
}
