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

    public Page<OperationWithProductInfoRow> searchAll(SearchRequest request){
        Specification<Operation> spec = OperationSpecifications.fromSearchRequest(request);
        Pageable pageable = PageableBuilder.from(request);
        return operationRepository.searchWithProjection(spec, pageable, OperationWithProductInfoRow.class);
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
        boolean normRequired = request.getNormRequired() == null || request.getNormRequired();
        operation.setNormRequired(normRequired);
        operation.setMinNorm(request.getMinNorm());
        operation.setMaxNorm(request.getMaxNorm());
        validateNormRules(operation);
        operation.setUnitsPerProduct(request.getUnitsPerProduct());
        // The norm is optional, and the date is the date a NORM applies from —
        // so an operation without one is left without a date rather than with a
        // date that dates nothing.
        operation.setNormDate(hasNormValue(request.getMinNorm(), request.getMaxNorm())
                ? request.getNormDate() : null);
        operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));

        // The norm history follows the columns this form just wrote. Without it
        // the operation would work to one number while the history still marked
        // another as the one in force.
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        long count = operationRepository.countByProduct_IdAndArchivedAtIsNull(operation.getProduct().getId());
        return new OperationWithProductInfoRow(
                operation.getId(),
                operation.getProduct().getId(),
                operation.getOpName(),
                operation.getProduct().getProductName(),
                operation.getMinNorm(),
                operation.getMaxNorm(),
                //operation.isNormRequired(),
                operation.getUnitsPerProduct(),
                operation.getNormDate(),
                operation.getWorkCodeCategory() != null ? operation.getWorkCodeCategory().getId() : null,
                count
        );
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
                    "Operacija se ne može arhivirati dok postoje nezavršeni nalozi: "
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
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(()-> new EntityNotFoundException("Product not found"));

        operation.setProduct(product);
        operation.setOpName(request.getOperationName());
        boolean normRequired = request.getNormRequired() == null || request.getNormRequired();
        operation.setNormRequired(normRequired);
        operation.setMinNorm(request.getMinNorm());
        operation.setMaxNorm(request.getMaxNorm());
        validateNormRules(operation);
        operation.setUnitsPerProduct(request.getUnitsPerProduct());
        // The norm is optional, and the date is the date a NORM applies from —
        // so an operation without one is left without a date rather than with a
        // date that dates nothing.
        operation.setNormDate(hasNormValue(request.getMinNorm(), request.getMaxNorm())
                ? request.getNormDate() : null);
        operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));
        operation = operationRepository.save(operation);

        // An operation created WITH a norm starts its history there, rather than
        // carrying a norm the version table never saw.
        normInForce.recordCurrentFromOperation(operation, normInForce.currentUser());

        long count = operationRepository.countByProduct_IdAndArchivedAtIsNull(operation.getProduct().getId());
        return new OperationWithProductInfoRow(
                operation.getId(),
                operation.getProduct().getId(),
                operation.getOpName(),
                operation.getProduct().getProductName(),
                operation.getMinNorm(),
                operation.getMaxNorm(),
                //operation.isNormRequired(),
                operation.getUnitsPerProduct(),
                operation.getNormDate(),
                operation.getWorkCodeCategory() != null ? operation.getWorkCodeCategory().getId() : null,
                count
        );
    }

    private static boolean hasNormValue(Integer minNorm, Integer maxNorm) {
        return minNorm != null || maxNorm != null;
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
