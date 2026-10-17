package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.common.WrongPasswordException;
import com.aleksandarparipovic.marel_app.operation.dto.*;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.operation.specification.OperationSpecifications;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormInForceService;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormVersionRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepository;
    private final ProductRepository productRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final OperationMapper operationMapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OperationDetailService operationDetailService;
    private final OperationNormInForceService normInForce;
    private final OperationNormVersionRepository normVersionRepository;
    private final com.aleksandarparipovic.marel_app.product_type_operation.ProductTypeOperationRepository productTypeOperationRepository;

    private WorkCodeCategory resolveWorkCodeCategory(Long id) {
        if (id == null) {
            return null;
        }
        return workCodeCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category not found"));
    }


    public List<OperationBasicInfoDto> getAllOperationsForProduct(Long id, LocalDate date){
        return operationRepository.findActiveOrArchivedAfterDate(id, date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .map(operationMapper::toBasicDto)
                .toList();
    }

    public List<OperationDto> getAllOperationsForProductDto(Long id, LocalDate date) {
        // One query for the whole product, not one per operation: the flag lives
        // on the norm version, and the caller draws every operation of a product.
        Set<Long> temporaryNorms = new HashSet<>(normVersionRepository.findOperationIdsWithTemporaryNorm(id));

        return operationRepository.findActiveOrArchivedAfterDate(id, date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .map(operation -> {
                    OperationDto dto = operationMapper.toDto(operation);
                    dto.setNormTemporary(temporaryNorms.contains(operation.getId()));
                    return dto;
                })
                .toList();
    }

    /**
     * The live operations carrying one work code category, by CODE.
     *
     * <p>For the full-day absences: a whole shift nobody came in is drawn as a
     * single operation across it, and the factory keeps one operation per
     * category for exactly that. Offered as a list rather than resolved to the
     * one, because the screen asking has to be able to say "there is none
     * configured" or "there are two" instead of silently drawing the wrong day —
     * the same reason {@code OperationRepository} returns a list here.
     *
     * <p>Read-only and transactional: the product is a lazy association and this
     * reports its name.
     */
    @Transactional(readOnly = true)
    public List<AbsenceOperationDto> getActiveOperationsByCategoryNo(String categoryNo) {
        return operationRepository.findActiveByWorkCodeCategoryNo(categoryNo)
                .stream()
                .map(operation -> new AbsenceOperationDto(
                        operation.getId(),
                        operation.getOpName(),
                        // Standalone since V65 — the absence operations carry no
                        // product any more, and the caller no longer needs one.
                        operation.getProduct() == null ? null : operation.getProduct().getId(),
                        operation.getProduct() == null ? null : operation.getProduct().getProductName(),
                        operation.getWorkCodeCategory().getId(),
                        operation.getWorkCodeCategory().getCategoryNo()))
                .toList();
    }

    /**
     * The standalone (osnovne) operations a karton row may pick from — the
     * rows belonging to no product: bolovanje, godišnji odmor, neplaćeno
     * odsustvo, neradni dan and whatever an administrator adds. Same dating
     * rule as the per-product options: live, or archived after the karton's
     * date so an old shift still shows what was picked back then.
     */
    public List<OperationBasicInfoDto> getStandaloneOperations(LocalDate date) {
        return operationRepository
                .findStandaloneActiveOrArchivedAfterDate(date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .map(operationMapper::toBasicDto)
                .toList();
    }

    public Page<OperationWithProductInfoRow> searchAll(SearchRequest request){
        Specification<Operation> spec = OperationSpecifications.fromSearchRequest(request);
        Pageable pageable = PageableBuilder.from(request);
        return operationRepository.searchWithProjection(spec, pageable, OperationWithProductInfoRow.class);
    }

    /** The operations board's KPI figures — one request for the whole page. */
    @Transactional(readOnly = true)
    public OperationStatsRow getStats() {
        return operationRepository.getStats();
    }

    @Transactional(readOnly = true)
    public OperationWithProductNameDto getOperation(Long id){
        /*Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operation not found"));
        return operationMapper.toDto(operation);*/
        return operationRepository.findByIdWithProduct(id)
                .orElseThrow(()->new IllegalArgumentException("Operation not found"));
    }

    @Transactional
    public OperationWithProductInfoRow updateOperation(Long id, OperationUpdateRequest request){

        Operation operation = operationRepository.findById(id)
                .orElseThrow(()-> new IllegalArgumentException("Operation not found"));

        operation.setOpName(request.getOperationName());
        if (operation.isStandalone()) {
            // A standalone operation keeps its invariants through every edit:
            // no norm of any kind, and never without a category.
            requireNoNormFields(request.getMinNorm(), request.getMaxNorm(),
                    request.getUnitsPerProduct(), request.getNormDate(), request.getTemporary());
            if (request.getWorkCodeCategoryId() == null) {
                throw new IllegalArgumentException("Osnovna operacija mora imati kategoriju rada");
            }
            operation.setNormRequired(false);
        } else {
            boolean normRequired = request.getNormRequired() == null || request.getNormRequired();
            operation.setNormRequired(normRequired);
            operation.setMinNorm(request.getMinNorm());
            operation.setMaxNorm(request.getMaxNorm());
            validateNormRules(operation);
            operation.setUnitsPerProduct(request.getUnitsPerProduct());
            applyNormDating(operation, request.getMinNorm(), request.getMaxNorm(),
                    request.getTemporary(), request.getNormDate());
        }
        operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));

        // The norm history follows the columns this form just wrote. Without it
        // the operation would work to one number while the history still marked
        // another as the one in force.
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        return toRow(operation);
    }

    /** The grid row for one operation, product or not. */
    private OperationWithProductInfoRow toRow(Operation operation) {
        Product product = operation.getProduct();
        long count = product == null
                ? 0L
                : operationRepository.countByProduct_IdAndArchivedAtIsNull(product.getId());
        return new OperationWithProductInfoRow(
                operation.getId(),
                product == null ? null : product.getId(),
                operation.getOpName(),
                product == null ? null : product.getProductName(),
                operation.getMinNorm(),
                operation.getMaxNorm(),
                operation.getUnitsPerProduct(),
                operation.getNormDate(),
                operation.getWorkCodeCategory() != null ? operation.getWorkCodeCategory().getId() : null,
                count,
                product == null ? null : product.getCatalogNumber(),
                operation.isTemporary(),
                operation.isStandalone()
        );
    }

    /**
     * A standalone operation has no norm, so a form sending any norm field for
     * one is not a smaller norm — it is a mistake, and it is said out loud.
     */
    private static void requireNoNormFields(Integer minNorm, Integer maxNorm,
                                            Integer unitsPerProduct, LocalDate normDate, Boolean temporary) {
        if (minNorm != null || maxNorm != null || unitsPerProduct != null
                || normDate != null || Boolean.TRUE.equals(temporary)) {
            throw new IllegalArgumentException("Osnovna operacija nema normu — polja norme moraju ostati prazna");
        }
    }

    /**
     * Archives an operation — but only once nothing still owes work on it.
     *
     * <p>Three things happen here that did not before, and each is deliberate:
     * <ul>
     *   <li>the caller must say WHY, and the reason is stored with the actor;
     *   <li>a live order that still owes pieces of this operation refuses the
     *       archive, with the orders named (see
     *       {@link OperationDetailService#getArchiveBlockers});
     *   <li>{@code archived_at} is stamped. Archiving used to clear the active
     *       flag only, which every list on the screen ignores — the operation
     *       stayed exactly where it was. An archive nobody can see is not one.
     * </ul>
     */
    @Transactional
    public void archiveOperation(Long id, String password, String reason, Authentication authentication) {

        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow();

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new WrongPasswordException("Wrong password");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Razlog arhiviranja je obavezan");
        }

        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        List<String> blockers = operationDetailService.getArchiveBlockers(id);
        if (!blockers.isEmpty()) {
            throw new IllegalStateException(
                    "Operacija se ne može arhivirati dok postoje otvoreni zahtevi ili nezavršeni nalozi: "
                            + String.join("; ", blockers));
        }

        operation.setActive(false);
        operation.setArchivedAt(OffsetDateTime.now());
        operation.setArchivedReason(reason.trim());
        operation.setArchivedBy(user);
    }

    /** Renames an operation and nothing else — the norm is not touched. */
    @Transactional
    public void renameOperation(Long id, String operationName) {
        String name = operationName == null ? "" : operationName.trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Naziv operacije je obavezan");
        }

        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        operation.setOpName(name);
    }

    @Transactional
    public OperationWithProductInfoRow create(OperationCreateRequest request){
        Operation operation = new Operation();

        if (Boolean.TRUE.equals(request.getStandalone())) {
            // An OSNOVNA operation: no product, no norm, never without a
            // category — the DB checks repeat all three.
            if (request.getProductId() != null) {
                throw new IllegalArgumentException("Osnovna operacija ne pripada proizvodu");
            }
            if (request.getWorkCodeCategoryId() == null) {
                throw new IllegalArgumentException("Osnovna operacija mora imati kategoriju rada");
            }
            requireNoNormFields(request.getMinNorm(), request.getMaxNorm(),
                    request.getUnitsPerProduct(), request.getNormDate(), request.getTemporary());
            operation.setStandalone(true);
            operation.setNormRequired(false);
        } else {
            if (request.getProductId() == null) {
                throw new IllegalArgumentException("Operacija mora pripadati proizvodu");
            }
            Product product = productRepository.findById(request.getProductId())
                    .orElseThrow(()-> new EntityNotFoundException("Product not found"));
            operation.setProduct(product);
            boolean normRequired = request.getNormRequired() == null || request.getNormRequired();
            operation.setNormRequired(normRequired);
            operation.setMinNorm(request.getMinNorm());
            operation.setMaxNorm(request.getMaxNorm());
            validateNormRules(operation);
            operation.setUnitsPerProduct(request.getUnitsPerProduct());
            applyNormDating(operation, request.getMinNorm(), request.getMaxNorm(),
                    request.getTemporary(), request.getNormDate());
        }

        operation.setOpName(request.getOperationName());
        operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));
        operation = operationRepository.save(operation);

        // An operation created WITH a norm starts its history there, rather than
        // carrying a norm the version table never saw.
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        return toRow(operation);
    }

    /**
     * Copies existing operations onto a product — the "dodaj iz kataloga" /
     * "preuzmi od proizvoda" flows on the product page. Each id names a LIVE
     * source operation; the copy takes the operation's definition (name,
     * category, norm, units, description) and starts its own norm history at
     * the copied values. What it deliberately does NOT take: the source's norm
     * version history, work logs, or archive state — the copy is a new
     * operation that happens to start where the source is today.
     *
     * <p>Duplicates are skipped, not refused: a name the target already
     * carries live (compared case-insensitively, matching
     * {@code uq_operations_product_op_name_ci}), an id repeated in the batch,
     * or a source already belonging to the target. The result names both
     * halves so the modal can say what happened.
     */
    @Transactional
    public CopyOperationsResult copyOperationsToProduct(CopyOperationsRequest request) {
        Product target = productRepository.findByIdAndArchivedAtIsNull(request.getTargetProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        Set<String> takenNames = operationRepository
                .findByProductIdAndArchivedAtIsNull(target.getId())
                .stream()
                .map(o -> o.getOpName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<String> copied = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        for (Long operationId : request.getOperationIds()) {
            if (operationId == null || !seenIds.add(operationId)) {
                continue;
            }
            Operation source = operationRepository.findById(operationId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Operacija nije pronađena: " + operationId));
            // A standalone source has no product to copy from — skipped, not
            // refused, like every other row the copy cannot take.
            if (source.isArchived() || source.getProduct() == null
                    || source.getProduct().getId().equals(target.getId())) {
                skipped.add(source.getOpName());
                continue;
            }
            if (!takenNames.add(source.getOpName().toLowerCase(java.util.Locale.ROOT))) {
                skipped.add(source.getOpName());
                continue;
            }

            Operation copy = new Operation();
            copy.setProduct(target);
            copy.setOpName(source.getOpName());
            copy.setDescription(source.getDescription());
            copy.setWorkCodeCategory(source.getWorkCodeCategory());
            copy.setNormRequired(source.isNormRequired());
            copy.setMinNorm(source.getMinNorm());
            copy.setMaxNorm(source.getMaxNorm());
            copy.setUnitsPerProduct(source.getUnitsPerProduct());
            copy.setNormDate(source.getNormDate());
            copy.setTemporary(source.isTemporary());
            copy = operationRepository.save(copy);

            // The copy starts its norm history where the source is today —
            // same reason create() records one: a norm the version table never
            // saw is a norm the audit trail cannot explain.
            normInForce.recordCurrentFromOperation(copy, normInForce.currentUser());
            copied.add(copy.getOpName());
        }

        return new CopyOperationsResult(copied, skipped);
    }

    /**
     * Copies a product type's TEMPLATE operations onto a product — the "preuzmi
     * operacije tipa" flow when a product is created or edited. Each id names a
     * template operation ({@code product_type_operations}); the copy takes its
     * definition (name, category, suggested norm, units, description) and starts
     * its own norm history there.
     *
     * <p>The template carries no norm DATE, so the copy is left undated: when it
     * has a suggested norm it is recorded as a provisional ("privremena") norm —
     * a norm without a date, by the operation's own definition — for the product's
     * real dated norm to replace later. What it deliberately does NOT bring: any
     * link back to the template. Once copied, the operation is fully independent;
     * editing or retiring the template never touches it (a snapshot).
     *
     * <p>Duplicates are skipped, not refused, exactly like
     * {@link #copyOperationsToProduct}: a name the target already carries live
     * (case-insensitively), an id repeated in the batch, or a retired template.
     */
    @Transactional
    public CopyOperationsResult copyTypeOperationsToProduct(CopyTypeOperationsRequest request) {
        Product target = productRepository.findByIdAndArchivedAtIsNull(request.getTargetProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        Set<String> takenNames = operationRepository
                .findByProductIdAndArchivedAtIsNull(target.getId())
                .stream()
                .map(o -> o.getOpName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<String> copied = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        for (Long templateId : request.getProductTypeOperationIds()) {
            if (templateId == null || !seenIds.add(templateId)) {
                continue;
            }
            var template = productTypeOperationRepository.findById(templateId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Operacija tipa nije pronađena: " + templateId));
            if (Boolean.FALSE.equals(template.getIsActive())) {
                skipped.add(template.getOpName());
                continue;
            }
            if (!takenNames.add(template.getOpName().toLowerCase(java.util.Locale.ROOT))) {
                skipped.add(template.getOpName());
                continue;
            }

            Operation copy = new Operation();
            copy.setProduct(target);
            copy.setOpName(template.getOpName());
            copy.setDescription(template.getDescription());
            copy.setWorkCodeCategory(template.getWorkCodeCategory());
            copy.setNormRequired(Boolean.TRUE.equals(template.getNormRequired()));
            copy.setMinNorm(template.getDefaultMinNorm());
            copy.setMaxNorm(template.getDefaultMaxNorm());
            copy.setUnitsPerProduct(template.getDefaultUnitsPerProduct());
            // The template has no date; a defaulted norm is therefore undated, which
            // is precisely what "privremena" means on an operation. The person
            // entering the product's real norm dates it then.
            copy.setNormDate(null);
            copy.setTemporary(hasNormValue(template.getDefaultMinNorm(), template.getDefaultMaxNorm()));
            copy = operationRepository.save(copy);

            normInForce.recordCurrentFromOperation(copy, normInForce.currentUser());
            copied.add(copy.getOpName());
        }

        return new CopyOperationsResult(copied, skipped);
    }

    private static boolean hasNormValue(Integer minNorm, Integer maxNorm) {
        return minNorm != null || maxNorm != null;
    }

    /**
     * Settles the norm's dating on the operation from what the form sent.
     *
     * <p>The date is the date a NORM applies from, so an operation without a
     * norm value carries neither a date nor the temporary flag. When a norm IS
     * present the two are mutually exclusive: "privremena" means the norm was
     * entered without a date on purpose, so it clears the date; a norm with a
     * date is never temporary. This mirrors the norm-version form and keeps the
     * derived norm-version row inside its {@code NOT is_temporary OR norm_date
     * IS NULL} check.
     */
    private static void applyNormDating(Operation operation, Integer minNorm, Integer maxNorm,
                                        Boolean temporary, LocalDate normDate) {
        boolean hasNorm = hasNormValue(minNorm, maxNorm);
        boolean isTemporary = hasNorm && Boolean.TRUE.equals(temporary);
        operation.setTemporary(isTemporary);
        operation.setNormDate(hasNorm && !isTemporary ? normDate : null);
    }

    private void validateNormRules(Operation operation) {
        if (!operation.isNormRequired()) {
            return;
        }
        if (!operation.isNormValueValid()) {
            throw new IllegalArgumentException("When normRequired is true, minNorm/maxNorm must be > 0 and minNorm <= maxNorm");
        }
    }
}
