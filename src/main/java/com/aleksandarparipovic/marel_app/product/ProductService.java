package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.LikePattern;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationMapper;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductCreateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductUpdateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationCountRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductStatsRow;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final ProductTypeRepository productTypeRepository;
    private final ProductionOrderLineItemRepository productionOrderLineItemRepository;
    private final SampleOrderLineItemRepository sampleOrderLineItemRepository;
    private final PasswordConfirmationService passwordConfirmation;

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

        String catalogNumber = blankToNull(request.getCatalogNumber());
        if (catalogNumber != null && productRepository.catalogNumberTakenByAnother(catalogNumber, null)) {
            throw new IllegalArgumentException("Proizvod sa tim kataloškim brojem već postoji.");
        }

        Product product = Product.builder()
                .productName(productName)
                .productCode(productCode == null || productCode.isBlank() ? null : productCode)
                .description(request.getDescription())
                .productType(resolveType(request.getProductTypeId()))
                .catalogNumber(catalogNumber)
                .subtype(blankToNull(request.getSubtype()))
                .supervisorName(blankToNull(request.getSupervisorName()))
                .displayName(blankToNull(request.getDisplayName()))
                .active(true)
                .build();

        return productMapper.toBaseRow(productRepository.save(product));
    }

    /**
     * Edit a product from its detail page, one field at a time. Null leaves a
     * field alone; a blank string clears an optional text field. The name is
     * the exception — a provided blank name is refused rather than treated as
     * "leave it", because a product without a name is not a product.
     */
    @Transactional
    @CacheEvict(value = "product-options", allEntries = true)
    public ProductBaseRow updateProduct(Long productId, ProductUpdateRequest request) {
        Product product = productRepository.findByIdAndArchivedAtIsNull(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        if (request.getProductName() != null) {
            String productName = request.getProductName().trim();
            if (productName.isEmpty()) {
                throw new IllegalArgumentException("Naziv proizvoda je obavezan.");
            }
            if (productRepository.productNameTakenByAnother(productName, productId)) {
                throw new IllegalArgumentException("Proizvod sa tim nazivom već postoji.");
            }
            product.setProductName(productName);
        }
        if (request.getProductCode() != null) {
            String productCode = blankToNull(request.getProductCode());
            if (productCode != null
                    && productRepository.productCodeTakenByAnother(productCode, productId)) {
                throw new IllegalArgumentException("Proizvod sa tim kodom već postoji.");
            }
            product.setProductCode(productCode);
        }
        if (request.getDescription() != null) {
            product.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getActive() != null) {
            product.setActive(request.getActive());
        }
        if (request.getProductTypeId() != null) {
            // 0 is the "clear it" sentinel — null already means "leave it".
            product.setProductType(request.getProductTypeId() == 0
                    ? null
                    : resolveType(request.getProductTypeId()));
        }
        if (request.getCatalogNumber() != null) {
            String catalogNumber = blankToNull(request.getCatalogNumber());
            if (catalogNumber != null
                    && productRepository.catalogNumberTakenByAnother(catalogNumber, productId)) {
                throw new IllegalArgumentException("Proizvod sa tim kataloškim brojem već postoji.");
            }
            product.setCatalogNumber(catalogNumber);
        }
        if (request.getSubtype() != null) {
            product.setSubtype(blankToNull(request.getSubtype()));
        }
        if (request.getSupervisorName() != null) {
            product.setSupervisorName(blankToNull(request.getSupervisorName()));
        }
        if (request.getDisplayName() != null) {
            product.setDisplayName(blankToNull(request.getDisplayName()));
        }

        return productMapper.toBaseRow(productRepository.save(product));
    }

    /** Resolve a product type by id, or null when none is given. */
    private ProductType resolveType(Long productTypeId) {
        if (productTypeId == null) {
            return null;
        }
        return productTypeRepository.findById(productTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Tip proizvoda nije pronađen: " + productTypeId));
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    /**
     * The sortable columns of the two order tables on the product page, by the
     * UI's field name. Whitelists, because the sort path goes into the query
     * verbatim via {@link JpaSort#unsafe} — an unknown field must answer 400,
     * not reach the database.
     */
    private static final Map<String, String> PRODUCTION_ORDER_SORTS = Map.of(
            "code", "po.code",
            "name", "po.name",
            "status", "po.status",
            "orderDate", "po.orderDate",
            "deliveryDeadline", "po.deliveryDeadline",
            "quantity", "li.quantity"
    );

    private static final Map<String, String> SAMPLE_ORDER_SORTS = Map.of(
            "name", "so.name",
            "status", "so.status",
            "creationDate", "so.creationDate",
            "deadlineDate", "so.deadlineDate",
            "quantity", "li.quantity",
            "catalogNo", "li.catalogNo"
    );

    /** Production orders this product appears on — searched and sorted server-side. */
    @Transactional(readOnly = true)
    public List<ProductProductionOrderRow> getProductProductionOrders(
            Long productId, String query, String sortBy, String direction) {
        requireProduct(productId);
        return productionOrderLineItemRepository.findOrderRowsByProductId(
                productId,
                toPattern(query),
                orderSort(PRODUCTION_ORDER_SORTS, sortBy, direction, "po.orderDate", "po.id"));
    }

    /** Sample orders this product appears on — searched and sorted server-side. */
    @Transactional(readOnly = true)
    public List<ProductSampleOrderRow> getProductSampleOrders(
            Long productId, String query, String sortBy, String direction) {
        requireProduct(productId);
        return sampleOrderLineItemRepository.findOrderRowsByProductId(
                productId,
                toPattern(query),
                orderSort(SAMPLE_ORDER_SORTS, sortBy, direction, "so.creationDate", "so.id"));
    }

    private static String toPattern(String query) {
        return (query == null || query.isBlank()) ? null : LikePattern.contains(query.trim());
    }

    /**
     * The caller's sort resolved against a whitelist, with the id as the
     * tie-breaker so the order is stable. No sort asked for = newest first.
     */
    private static Sort orderSort(Map<String, String> whitelist, String sortBy,
                                  String direction, String defaultPath, String idPath) {
        Sort.Direction dir = "ASC".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String path;
        if (sortBy == null || sortBy.isBlank()) {
            path = defaultPath;
            dir = Sort.Direction.DESC;
        } else {
            path = whitelist.get(sortBy);
            if (path == null) {
                throw new IllegalArgumentException("Nepoznata kolona za sortiranje: " + sortBy);
            }
        }
        return JpaSort.unsafe(dir, path).and(JpaSort.unsafe(Sort.Direction.DESC, idPath));
    }

    /**
     * Why this product cannot be archived right now, as sentences the modal can
     * print. An empty list means it may be archived. The rule mirrors the
     * operation-level one a level up: no LIVE order may still be counting on
     * this product — a production order blocks until it is delivered, a sample
     * order until it is closed.
     */
    @Transactional(readOnly = true)
    public List<String> getArchiveBlockers(Long productId) {
        requireProduct(productId);
        List<String> blockers = new ArrayList<>();

        for (ProductProductionOrderRow order : productionOrderLineItemRepository
                .findOrderRowsByProductId(productId, null,
                        JpaSort.unsafe(Sort.Direction.DESC, "po.orderDate"))) {
            if (order.status() != ProductionOrderStatus.DELIVERED) {
                blockers.add("Nalog %s nije isporučen".formatted(order.code()));
            }
        }

        for (ProductSampleOrderRow sample : sampleOrderLineItemRepository
                .findOrderRowsByProductId(productId, null,
                        JpaSort.unsafe(Sort.Direction.DESC, "so.creationDate"))) {
            if (!CLOSED_SAMPLE_STATUS.equalsIgnoreCase(sample.status())) {
                blockers.add("Nalog za uzorak „%s“ nije zatvoren".formatted(sample.name()));
            }
        }

        return blockers;
    }

    /** The one sample-order status that means the work is over (see OperationDetailService). */
    private static final String CLOSED_SAMPLE_STATUS = "closed";

    /**
     * Archives a product, with the caller's password as the signature under
     * the action. The product's live operations go with it, marked
     * {@code archivedByProduct} so a restore knows which ones to bring back —
     * an operation archived on its own stays archived either way.
     */
    @Transactional
    @CacheEvict(value = "product-options", allEntries = true)
    public void archiveProduct(Long productId, String password, Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);

        Product product = productRepository.findByIdAndArchivedAtIsNull(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        List<String> blockers = getArchiveBlockers(productId);
        if (!blockers.isEmpty()) {
            throw new ConflictException(
                    "Proizvod se ne može arhivirati dok postoje otvoreni nalozi: "
                            + String.join("; ", blockers));
        }

        for (Operation operation : operationRepository.findByProductIdAndArchivedAtIsNull(productId)) {
            operation.archive();
            operation.setArchivedByProduct(true);
        }
        product.archive();
    }

    /** Brings an archived product back, together with the operations its archive took down. */
    @Transactional
    @CacheEvict(value = "product-options", allEntries = true)
    public void restoreProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        if (!product.isArchived()) {
            return;
        }
        product.reactivate();
        for (Operation operation : operationRepository.findByProductIdAndArchivedByProductTrue(productId)) {
            operation.reactivate();
            operation.setArchivedByProduct(false);
        }
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

    /** The product board's KPI figures — one request for the whole page. */
    @Transactional(readOnly = true)
    public ProductStatsRow getStats() {
        long total = productRepository.countByArchivedAtIsNull();
        long active = productRepository.countByArchivedAtIsNullAndActiveTrue();
        long withoutOperations = productRepository.countWithoutLiveOperations();
        long totalOperations = productRepository.countLiveOperations();
        return new ProductStatsRow(total, active, total - active, withoutOperations, totalOperations);
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
