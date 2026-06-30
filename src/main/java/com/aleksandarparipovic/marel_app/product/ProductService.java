package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.operation.OperationMapper;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductCreateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationCountRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product.specification.ProductSpecifications;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final OperationRepository operationRepository;
    private final OperationMapper operationMapper;
    private final ProductMapper productMapper;

    @Transactional
    @CacheEvict(value = "product-options", allEntries = true)
    public ProductBaseRow createProduct(ProductCreateRequest request) {
        String productName = request.getProductName().trim();

        if (productRepository.existsByProductNameIgnoreCaseAndArchivedAtIsNull(productName)) {
            throw new IllegalArgumentException("Product with this name already exists");
        }

        String productCode = request.getProductCode() == null ? null : request.getProductCode().trim();
        if (productCode != null && !productCode.isBlank()
                && productRepository.existsByProductCodeIgnoreCaseAndArchivedAtIsNull(productCode)) {
            throw new IllegalArgumentException("Product with this code already exists");
        }

        Product product = Product.builder()
                .productName(productName)
                .productCode(productCode == null || productCode.isBlank() ? null : productCode)
                .description(request.getDescription())
                .active(true)
                .build();

        return productMapper.toBaseRow(productRepository.save(product));
    }

    public List<ProductOptionDto> getAllProducts(){
        return productRepository.findByArchivedAtIsNullOrderByProductNameAsc()
                .stream()
                .map(productMapper::toDtoOption)
                .toList();
    }

    public Page<ProductWithOperationListRow> searchAll(SearchRequest request) {

        Specification<Product> specification =
                ProductSpecifications.fromSearchRequest(request);

        Pageable pageable = PageableBuilder.from(request);

        Page<ProductWithOperationCountRow> page =
                productRepository.searchWithProjection(
                        specification,
                        pageable,
                        ProductWithOperationCountRow.class
                );

        List<Long> productIds = page.getContent()
                .stream()
                .map(ProductWithOperationCountRow::getProductId)
                .toList();

        Map<Long, List<OperationDto>> grouped =
                operationRepository
                        .findByProductIdInAndArchivedAtIsNull(productIds)
                        .stream()
                        .map(operationMapper::toDto)
                        .collect(Collectors.groupingBy(OperationDto::getProductId));

        List<ProductWithOperationListRow> enriched =
                page.getContent()
                        .stream()
                        .map(row -> new ProductWithOperationListRow(
                                row,
                                grouped.getOrDefault(row.getProductId(), List.of())
                        ))
                        .toList();


        return new PageImpl<>(
                enriched,
                pageable,
                page.getTotalElements()
        );
    }
}
