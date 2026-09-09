package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeOperationRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.ProductManufacturingTimeOperation;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.ProductManufacturingTimeOperationRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequest;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestRepository;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestStatus;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeStatsRow;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductManufacturingTimeService {

    private final ProductManufacturingTimeRepository repository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OperationRepository operationRepository;
    private final ProductManufacturingTimeOperationRepository pmtoRepository;
    private final ManufacturingTimeRequestRepository requestRepository;

    private static final ProductManufacturingTimeFieldMapper FIELD_MAPPER =
            new ProductManufacturingTimeFieldMapper();

    @Transactional
    public ProductManufacturingTimeDto create(ProductManufacturingTimeCreateRequest req, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        ProductManufacturingTime entity = new ProductManufacturingTime();
        entity.setUser(user);
        entity.setTitle(req.getTitle());
        entity.setNote(req.getNote());
        entity.setProduct(product);
        entity.setProductName(req.getProductName());
        entity.setDateOfIssue(LocalDate.now());
        entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        entity.setProductsPerHour(req.getProductsPerHour());
        entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        entity.setActive(true);

        ProductManufacturingTime saved = repository.save(entity);
        saveOperations(saved, req.getOperations());
        return toDto(saved);
    }

    /**
     * Same as {@link #create} but with the acting user passed explicitly, for the
     * request workflow where the processor is resolved from the request rather
     * than re-read from the Authentication. Returns the entity so the caller can
     * link it to its source request inside the same transaction.
     */
    @Transactional
    public ProductManufacturingTime createForUser(ProductManufacturingTimeCreateRequest req, User user) {
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        ProductManufacturingTime entity = new ProductManufacturingTime();
        entity.setUser(user);
        entity.setTitle(req.getTitle());
        entity.setNote(req.getNote());
        entity.setProduct(product);
        entity.setProductName(req.getProductName());
        entity.setDateOfIssue(LocalDate.now());
        entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        entity.setProductsPerHour(req.getProductsPerHour());
        entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        entity.setActive(true);

        ProductManufacturingTime saved = repository.save(entity);
        saveOperations(saved, req.getOperations());
        return saved;
    }

    /** Applies an update and returns the entity, for the request workflow. */
    @Transactional
    public ProductManufacturingTime applyUpdate(Long id, ProductManufacturingTimeUpdateRequest req) {
        update(id, req);
        return getActiveOrThrow(id);
    }

    @Transactional
    public ProductManufacturingTimeDto update(Long id, ProductManufacturingTimeUpdateRequest req) {
        ProductManufacturingTime entity = getActiveOrThrow(id);

        if (req.getManufacturingCoefficient() != null) entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        if (req.getProductsPerHour() != null) entity.setProductsPerHour(req.getProductsPerHour());
        if (req.getManufacturingTimeSeconds() != null) entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        if (req.getTitle() != null) entity.setTitle(req.getTitle());
        // Not guarded on null: erasing the note is an edit like any other, and a
        // guard would make it the one change the screen cannot make.
        entity.setNote(req.getNote());

        if (req.getOperations() != null) {
            pmtoRepository.deactivateAllByProductManufacturingTimeId(entity.getId());
            saveOperations(entity, req.getOperations());
        }

        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public ProductManufacturingTimeDto getById(Long id) {
        return toDto(getActiveOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByUserId(Long userId) {
        return repository.findByUser_IdAndActiveTrueOrderByDateOfIssueDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Everything the request workflow has produced, whoever produced it. */
    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getAnsweringRequests() {
        return repository.findAnsweringRequests()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getForCurrentUser(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return repository.findByUser_IdAndActiveTrueOrderByDateOfIssueDesc(user.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─── The board: paged, filtered, sorted on the server ───────────────────

    /** The caller's own list, as one page the server searched and ordered. */
    @Transactional(readOnly = true)
    public Page<ProductManufacturingTimeDto> searchMine(SearchRequest request, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        Specification<ProductManufacturingTime> spec = activeOnly()
                .and(ownedBy(user.getId()))
                .and(new SearchSpecification<>(request, FIELD_MAPPER));
        return toDtoPage(repository.findAll(spec, pageableFor(request)));
    }

    /** The shared list — records that answer a request — under the same controls. */
    @Transactional(readOnly = true)
    public Page<ProductManufacturingTimeDto> searchFromRequests(SearchRequest request) {
        Specification<ProductManufacturingTime> spec = activeOnly()
                .and(answersSomeRequest())
                .and(new SearchSpecification<>(request, FIELD_MAPPER));
        return toDtoPage(repository.findAll(spec, pageableFor(request)));
    }

    /**
     * The page's KPI figures. {@code restrictToCreatedById} carries the same
     * narrowing the request picker applies: the caller's own id when they may not
     * read everybody's requests, NULL when they may.
     */
    @Transactional(readOnly = true)
    public ProductManufacturingTimeStatsRow getStats(Long currentUserId, Long restrictToCreatedById) {
        long pending = restrictToCreatedById == null
                ? requestRepository.countByStatus(ManufacturingTimeRequestStatus.PENDING)
                : requestRepository.countByStatusAndCreatedBy_Id(
                        ManufacturingTimeRequestStatus.PENDING, restrictToCreatedById);
        return new ProductManufacturingTimeStatsRow(
                repository.countByUser_IdAndActiveTrue(currentUserId),
                repository.countAnsweringRequests(),
                pending,
                requestRepository.countByStatusAndAssignedTo_Id(
                        ManufacturingTimeRequestStatus.IN_REVIEW, currentUserId)
        );
    }

    private static Specification<ProductManufacturingTime> activeOnly() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    private static Specification<ProductManufacturingTime> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    /** The same fact {@code findAnsweringRequests} reads, as a composable predicate. */
    private static Specification<ProductManufacturingTime> answersSomeRequest() {
        return (root, query, cb) -> {
            Subquery<Long> answering = query.subquery(Long.class);
            var request = answering.from(ManufacturingTimeRequest.class);
            answering.select(cb.literal(1L))
                    .where(cb.equal(request.get("resultManufacturingTime").get("id"), root.get("id")));
            return cb.exists(answering);
        };
    }

    /**
     * The requested page, with the list's own default order when none was asked
     * for: newest first, id as the tiebreaker so same-day records hold still.
     */
    private static Pageable pageableFor(SearchRequest request) {
        Pageable pageable = PageableBuilder.from(request);
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Order.desc("dateOfIssue"), Sort.Order.desc("id")));
    }

    /**
     * A page of records to a page of DTOs in three queries total — the lines of
     * every record on the page at once, and the answering flag for all of them at
     * once — instead of a query per row.
     */
    private Page<ProductManufacturingTimeDto> toDtoPage(Page<ProductManufacturingTime> page) {
        List<Long> ids = page.getContent().stream().map(ProductManufacturingTime::getId).toList();
        if (ids.isEmpty()) {
            return page.map(e -> new ProductManufacturingTimeDto(e, List.of()));
        }
        Map<Long, List<ProductManufacturingTimeOperationDto>> operationsByRecord =
                pmtoRepository.findByProductManufacturingTime_IdInAndActiveTrueOrderByIdAsc(ids)
                        .stream()
                        .collect(Collectors.groupingBy(
                                op -> op.getProductManufacturingTime().getId(),
                                Collectors.mapping(ProductManufacturingTimeOperationDto::new, Collectors.toList())));
        Set<Long> answering = repository.findAnsweringIdsAmong(ids);
        return page.map(e -> new ProductManufacturingTimeDto(
                e,
                operationsByRecord.getOrDefault(e.getId(), List.of()),
                answering.contains(e.getId())));
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByProductId(Long productId) {
        return repository.findByProduct_IdAndActiveTrue(productId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByProductIdAndDateRange(Long productId, LocalDate from, LocalDate to) {
        return repository.findByProductIdAndDateRange(productId, from, to)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        ProductManufacturingTime entity = getActiveOrThrow(id);
        /*
         * A record some request points at is the company's answer to somebody's
         * ask, not a private draft — removing it would leave the request pointing
         * at nothing. Retiring one goes through a DEACTIVATE request, where the
         * decision is recorded, so the delete refuses rather than obliges.
         */
        if (repository.answersAnyRequest(id)) {
            throw new ConflictException(
                    "Ovo vreme izrade je odgovor na zahtev i ne može da se obriše ovde. "
                            + "Za povlačenje podnesite zahtev za deaktivaciju.");
        }
        entity.setActive(false);
        pmtoRepository.deactivateAllByProductManufacturingTimeId(id);
    }

    public ProductManufacturingTime getActiveOrThrow(Long id) {
        ProductManufacturingTime entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ProductManufacturingTime not found with id: " + id));
        if (!Boolean.TRUE.equals(entity.getActive())) {
            throw new EntityNotFoundException("ProductManufacturingTime not found with id: " + id);
        }
        return entity;
    }

    private void saveOperations(ProductManufacturingTime pmt, List<ProductManufacturingTimeOperationRequest> requests) {
        if (requests == null || requests.isEmpty()) return;

        for (ProductManufacturingTimeOperationRequest opReq : requests) {
            Operation operation = operationRepository.findById(opReq.getOperationId())
                    .orElseThrow(() -> new EntityNotFoundException("Operation not found with id: " + opReq.getOperationId()));

            ProductManufacturingTimeOperation op = new ProductManufacturingTimeOperation();
            op.setProductManufacturingTime(pmt);
            op.setOperation(operation);
            op.setOperationName(opReq.getOperationName());
            op.setUnitsPerProductSnapshot(opReq.getUnitsPerProductSnapshot());
            op.setUnitsPerProductOverridden(Boolean.TRUE.equals(opReq.getUnitsPerProductOverridden()));
            op.setUnitsPerProductValue(opReq.getUnitsPerProductValue());
            op.setNormSnapshot(opReq.getNormSnapshot());
            op.setNormOverridden(Boolean.TRUE.equals(opReq.getNormOverridden()));
            op.setNormValue(opReq.getNormValue());
            op.setNormDateSnapshot(opReq.getNormDateSnapshot());
            op.setNormDateOverridden(Boolean.TRUE.equals(opReq.getNormDateOverridden()));
            op.setNormDateValue(opReq.getNormDateValue());
            op.setNormDateNote(opReq.getNormDateNote());
            op.setNote(opReq.getNote());
            op.setExcluded(Boolean.TRUE.equals(opReq.getExcluded()));
            op.setActive(true);
            pmtoRepository.save(op);
        }
    }

    private ProductManufacturingTimeDto toDto(ProductManufacturingTime entity) {
        List<ProductManufacturingTimeOperationDto> operations =
                pmtoRepository.findByProductManufacturingTime_IdAndActiveTrue(entity.getId())
                        .stream()
                        .map(ProductManufacturingTimeOperationDto::new)
                        .toList();
        return new ProductManufacturingTimeDto(entity, operations);
    }
}
