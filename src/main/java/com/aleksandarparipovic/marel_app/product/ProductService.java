package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.operation.OperationMapper;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductCreateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationCountRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.sample_order_line_item.repository.SampleOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.product.specification.ProductSpecifications;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
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
    private final ProductionOrderLineItemRepository productionOrderLineItemRepository;
    private final SampleOrderLineItemRepository sampleOrderLineItemRepository;

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

    /** One product, for the product detail page. Archived products are not served. */
    @Transactional(readOnly = true)
    public ProductBaseRow getProduct(Long productId) {
        Product product = productRepository.findByIdAndArchivedAtIsNull(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        return productMapper.toBaseRow(product);
    }

    /** The product's live operations, in name order — norms included. */
    @Transactional(readOnly = true)
    public List<OperationDto> getProductOperations(Long productId) {
        requireProduct(productId);
        return operationRepository.findByProductIdAndArchivedAtIsNull(productId)
                .stream()
                .map(operationMapper::toDto)
                .sorted(Comparator.comparing(
                        OperationDto::getOperationName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Production orders this product appears on. */
    @Transactional(readOnly = true)
    public List<ProductProductionOrderRow> getProductProductionOrders(Long productId) {
        requireProduct(productId);
        return productionOrderLineItemRepository.findOrderRowsByProductId(productId);
    }

    /** Sample orders this product appears on. */
    @Transactional(readOnly = true)
    public List<ProductSampleOrderRow> getProductSampleOrders(Long productId) {
        requireProduct(productId);
        return sampleOrderLineItemRepository.findOrderRowsByProductId(productId);
    }

    /**
     * A missing product must answer 404 rather than an empty list — an empty
     * list means "this product is on no orders", which is a different fact.
     */
    private void requireProduct(Long productId) {
        if (productRepository.findByIdAndArchivedAtIsNull(productId).isEmpty()) {
            throw new EntityNotFoundException("Product not found");
        }
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
