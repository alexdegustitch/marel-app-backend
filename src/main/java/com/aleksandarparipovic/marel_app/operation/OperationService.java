package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.WrongPasswordException;
import com.aleksandarparipovic.marel_app.operation.dto.*;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.operation.specification.OperationSpecifications;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormInForceService;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormVersionRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_variant.ProductVariant;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOption;
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
import java.util.Map;
import java.util.Objects;
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
    private final com.aleksandarparipovic.marel_app.product_variant.ProductVariantRepository productVariantRepository;
    private final com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOptionRepository variantAttributeOptionRepository;

    private WorkCodeCategory resolveWorkCodeCategory(Long id) {
        if (id == null) {
            return null;
        }
        return workCodeCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category not found"));
    }


    @Transactional(readOnly = true)
    public List<OperationBasicInfoDto> getAllOperationsForProduct(Long id, LocalDate date, Long attributeOptionId){
        /*
         * V77, the karton's view: with no filter, every operation except the
         * dead-bound ones (a value no live variant carries); with one
         * attribute value, the unbound plus those bound exactly to it. Same
         * sentence as the DTO list below, kept in step with it.
         */
        Set<Long> liveOptionIds = attributeOptionId != null ? null
                : productVariantRepository.findByProduct_IdAndArchivedAtIsNullOrderByIdAsc(id)
                        .stream()
                        .flatMap(v -> v.getValues().stream())
                        .map(v -> v.getOption().getId())
                        .collect(java.util.stream.Collectors.toSet());

        return operationRepository.findActiveOrArchivedAfterDate(id, date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .filter(operation -> {
                    List<OperationVariantOption> bindings = operation.getVariantBindings();
                    if (bindings.isEmpty()) {
                        return true;
                    }
                    if (attributeOptionId != null) {
                        return bindings.stream().anyMatch(b -> b.getOption().getId().equals(attributeOptionId));
                    }
                    return bindings.stream().allMatch(b -> liveOptionIds.contains(b.getOption().getId()));
                })
                .map(operationMapper::toBasicDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OperationDto> getAllOperationsForProductDto(Long id, LocalDate date,
                                                            Long variantId, Long attributeOptionId) {
        // One query for the whole product, not one per operation: the flag lives
        // on the norm version, and the caller draws every operation of a product.
        Set<Long> temporaryNorms = new HashSet<>(normVersionRepository.findOperationIdsWithTemporaryNorm(id));

        /*
         * V77 — which operations the caller may see is a question about the
         * BINDING. With a variant: its option values decide. With one attribute
         * value (the karton filter): that value decides. With neither: an
         * operation bound to a value NO live variant carries is "dead" and is
         * not offered — it waits for such a variant to exist (SPEC 5.1).
         */
        Set<Long> allowedOptionIds = null;
        if (variantId != null) {
            ProductVariant variant = productVariantRepository.findWithValuesById(variantId)
                    .orElseThrow(() -> new EntityNotFoundException("Varijanta nije pronađena: " + variantId));
            if (!variant.getProduct().getId().equals(id)) {
                throw new IllegalArgumentException("Varijanta ne pripada ovom proizvodu.");
            }
            allowedOptionIds = variant.getValues().stream()
                    .map(v -> v.getOption().getId())
                    .collect(java.util.stream.Collectors.toSet());
        } else if (attributeOptionId == null) {
            allowedOptionIds = productVariantRepository
                    .findByProduct_IdAndArchivedAtIsNullOrderByIdAsc(id)
                    .stream()
                    .flatMap(v -> v.getValues().stream())
                    .map(v -> v.getOption().getId())
                    .collect(java.util.stream.Collectors.toSet());
        }
        final Set<Long> allowed = allowedOptionIds;

        return operationRepository.findActiveOrArchivedAfterDate(id, date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .stream()
                .filter(operation -> {
                    List<OperationVariantOption> bindings = operation.getVariantBindings();
                    if (bindings.isEmpty()) {
                        return true; // product-level: applies to every variant
                    }
                    if (attributeOptionId != null) {
                        return bindings.stream()
                                .anyMatch(b -> b.getOption().getId().equals(attributeOptionId));
                    }
                    return bindings.stream().allMatch(b -> allowed.contains(b.getOption().getId()));
                })
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

        // V77: the step moves only when the form says one; group members keep
        // the same step AND the same units by invariant, so a member's change
        // moves them all — the alternative is a group whose union means nothing.
        if (request.getSortOrder() != null && !request.getSortOrder().equals(operation.getSortOrder())) {
            if (operation.getOperationGroup() != null) {
                for (Operation member : operationRepository.findByOperationGroup_Id(
                        operation.getOperationGroup().getId())) {
                    member.setSortOrder(request.getSortOrder());
                    operationRepository.save(member);
                }
            } else {
                operation.setSortOrder(request.getSortOrder());
            }
        }
        if (operation.getOperationGroup() != null) {
            for (Operation member : operationRepository.findByOperationGroup_Id(
                    operation.getOperationGroup().getId())) {
                if (!member.getId().equals(operation.getId())
                        && !java.util.Objects.equals(member.getUnitsPerProduct(), operation.getUnitsPerProduct())) {
                    member.setUnitsPerProduct(operation.getUnitsPerProduct());
                    operationRepository.save(member);
                }
            }
        }

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
                operation.isStandalone(),
                operation.getDescription()
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

    /**
     * Changes the work code category and nothing else — the norm is not touched.
     * The old value stays readable: the operations_history trigger records the
     * change, and the page's "Istorija izmena" reads it back.
     */
    @Transactional
    public void changeWorkCodeCategory(Long id, Long workCodeCategoryId) {
        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        // The same invariant every other edit path keeps.
        if (operation.isStandalone() && workCodeCategoryId == null) {
            throw new IllegalArgumentException("Osnovna operacija mora imati kategoriju rada");
        }
        operation.setWorkCodeCategory(resolveWorkCodeCategory(workCodeCategoryId));
    }

    /**
     * Changes units-per-product on its own, without opening the norm dialog.
     *
     * <p>The value still lives alongside the norm in the version history —
     * {@link OperationNormInForceService} keeps the operation's columns and the
     * version in force in agreement, and payroll reads the columns — so the
     * change is recorded there too, exactly as the edit form's path does it.
     */
    /**
     * V77 — the whole execution order of one product's operations, as one
     * transaction (the "Redosled izrade" drawer's save). The rules are the
     * single-step PATCH's, held across the batch:
     * <ul>
     *   <li>every named operation belongs to the product and is not standalone;
     *   <li>steps are 1-based; null unplaces;
     *   <li>group members share the step: naming ONE member moves the whole
     *       group, and naming two members of a group with DIFFERENT steps is a
     *       contradiction the request is refused for;
     *   <li>operations the request does not name keep the step they had.
     * </ul>
     */
    @Transactional
    public void reorderSteps(com.aleksandarparipovic.marel_app.operation.dto.OperationStepsRequest request) {
        Map<Long, Integer> wanted = new java.util.LinkedHashMap<>();
        for (var step : request.steps()) {
            if (step.sortOrder() != null && step.sortOrder() < 1) {
                throw new IllegalArgumentException("Korak mora biti najmanje 1.");
            }
            if (wanted.put(step.operationId(), step.sortOrder()) != null) {
                throw new IllegalArgumentException(
                        "Operacija " + step.operationId() + " je navedena dvaput.");
            }
        }

        List<Operation> named = operationRepository.findAllById(wanted.keySet());
        if (named.size() != wanted.size()) {
            throw new EntityNotFoundException("Neka od navedenih operacija ne postoji.");
        }

        // What each GROUP is asked to be — one answer per group, or a refusal.
        Map<Long, Integer> groupWanted = new java.util.LinkedHashMap<>();
        for (Operation operation : named) {
            if (operation.getProduct() == null || !operation.getProduct().getId().equals(request.productId())) {
                throw new ConflictException(
                        "Operacija „" + operation.getOpName() + "“ ne pripada ovom proizvodu.");
            }
            Integer step = wanted.get(operation.getId());
            if (operation.getOperationGroup() == null) {
                operation.setSortOrder(step);
                operationRepository.save(operation);
                continue;
            }
            Long groupId = operation.getOperationGroup().getId();
            if (groupWanted.containsKey(groupId)
                    && !Objects.equals(groupWanted.get(groupId), step)) {
                throw new ConflictException("Članovi grupe „"
                        + operation.getOperationGroup().getName()
                        + "“ moraju biti isti korak — zahtev im daje dva različita.");
            }
            groupWanted.put(groupId, step);
        }
        for (var entry : groupWanted.entrySet()) {
            for (Operation member : operationRepository.findByOperationGroup_Id(entry.getKey())) {
                member.setSortOrder(entry.getValue());
                operationRepository.save(member);
            }
        }
    }

    /**
     * V77 — the step ("korak") alone. Equal steps run in parallel, null is
     * unplaced. Group members share the step by invariant, so a member's change
     * moves the whole group — otherwise the group's union would straddle two
     * steps and mean nothing.
     */
    @Transactional
    public void changeSortOrder(Long id, Integer sortOrder) {
        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));
        if (operation.isStandalone()) {
            throw new IllegalArgumentException("Osnovna operacija ne pripada proizvodu, pa nema korak.");
        }
        if (sortOrder != null && sortOrder < 1) {
            throw new IllegalArgumentException("Korak mora biti najmanje 1.");
        }
        if (Objects.equals(operation.getSortOrder(), sortOrder)) {
            return;
        }
        if (operation.getOperationGroup() != null) {
            for (Operation member : operationRepository.findByOperationGroup_Id(
                    operation.getOperationGroup().getId())) {
                member.setSortOrder(sortOrder);
                operationRepository.save(member);
            }
        } else {
            operation.setSortOrder(sortOrder);
            operationRepository.save(operation);
        }
    }

    @Transactional
    public void changeUnitsPerProduct(Long id, Integer unitsPerProduct) {
        Operation operation = operationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        if (operation.isStandalone()) {
            throw new IllegalArgumentException(
                    "Osnovna operacija nema normu — polja norme moraju ostati prazna");
        }
        if (unitsPerProduct != null && unitsPerProduct < 1) {
            throw new IllegalArgumentException("Količina u sklopu mora biti najmanje 1");
        }

        operation.setUnitsPerProduct(unitsPerProduct);
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        // V77: group members share units_per_product by invariant — a member's
        // change carries to the rest, each recording its own norm snapshot.
        if (operation.getOperationGroup() != null) {
            for (Operation member : operationRepository.findByOperationGroup_Id(
                    operation.getOperationGroup().getId())) {
                if (!member.getId().equals(operation.getId())
                        && !java.util.Objects.equals(member.getUnitsPerProduct(), unitsPerProduct)) {
                    member.setUnitsPerProduct(unitsPerProduct);
                    operationRepository.save(member);
                    normInForce.recordCurrentFromOperation(member, normInForce.currentUser());
                }
            }
        }
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
        operation.setDescription(request.getDescription());
        operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));
        operation.setSortOrder(request.getSortOrder());

        // V77: the binding travels with creation so the AddOperationsDrawer can
        // say "važi za Prohrom" in one request. Group membership is NOT set
        // here — a group has its own invariants and its own endpoints.
        if (request.getVariantAttributeOptionId() != null) {
            if (operation.getProduct() == null) {
                throw new IllegalArgumentException("Osnovna operacija nema varijante, pa ni binding.");
            }
            applyBinding(operation, request.getVariantAttributeOptionId());
        }
        operation = operationRepository.save(operation);

        // An operation created WITH a norm starts its history there, rather than
        // carrying a norm the version table never saw.
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        return toRow(operation);
    }

    /**
     * Sets or clears the binding: the attribute value this operation exists
     * for (SPEC odluka 4 — exactly one value, or none).
     *
     * <p>On a group member the change is applied to EVERY member: the members
     * share the binding set by invariant, and changing them together is the
     * only edit that keeps it true.
     */
    @Transactional
    public void setBinding(Long operationId, Long variantAttributeOptionId) {
        Operation operation = operationRepository.findById(operationId)
                .orElseThrow(() -> new EntityNotFoundException("Operacija nije pronađena: " + operationId));
        if (operation.getProduct() == null) {
            throw new IllegalArgumentException("Osnovna operacija nema varijante, pa ni binding.");
        }
        List<Operation> targets = operation.getOperationGroup() == null
                ? List.of(operation)
                : operationRepository.findByOperationGroup_Id(operation.getOperationGroup().getId());
        for (Operation target : targets) {
            if (variantAttributeOptionId == null) {
                target.getVariantBindings().clear();
            } else {
                applyBinding(target, variantAttributeOptionId);
            }
            operationRepository.save(target);
        }
    }

    /** Replaces the operation's binding with the one option, after the checks. */
    private void applyBinding(Operation operation, Long optionId) {
        VariantAttributeOption option = variantAttributeOptionRepository.findById(optionId)
                .orElseThrow(() -> new EntityNotFoundException("Opcija varijantnog atributa nije pronađena: " + optionId));
        Product product = operation.getProduct();
        if (product.getProductType() == null
                || !option.getAttribute().getProductType().getId().equals(product.getProductType().getId())) {
            throw new IllegalArgumentException("Opcija ne pripada tipu ovog proizvoda.");
        }
        if (option.isArchived() || option.getAttribute().isArchived()) {
            throw new IllegalArgumentException(
                    "Opcija „" + option.getValue() + "“ je arhivirana i ne ulazi u nove bindinge.");
        }
        operation.getVariantBindings().clear();
        operation.getVariantBindings().add(OperationVariantOption.builder()
                .operation(operation)
                .attribute(option.getAttribute())
                .option(option)
                .build());
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

            /*
             * V77 — the binding travels only where it keeps meaning something:
             * onto a product of the SAME type (the option is the type's). A
             * different type drops it. Group membership is never copied — the
             * group is the source product's own set of machines.
             */
            Long sourceTypeId = source.getProduct().getProductType() == null
                    ? null : source.getProduct().getProductType().getId();
            Long targetTypeId = target.getProductType() == null
                    ? null : target.getProductType().getId();
            if (sourceTypeId != null && sourceTypeId.equals(targetTypeId)) {
                for (OperationVariantOption binding : source.getVariantBindings()) {
                    copy.getVariantBindings().add(OperationVariantOption.builder()
                            .operation(copy)
                            .attribute(binding.getAttribute())
                            .option(binding.getOption())
                            .build());
                }
            }
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
