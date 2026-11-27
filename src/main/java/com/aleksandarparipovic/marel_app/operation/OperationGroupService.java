package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.operation.dto.OperationGroupDtos.*;
import com.aleksandarparipovic.marel_app.operation.repository.OperationGroupRepository;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Groups of interchangeable operations — the same job on different machines.
 *
 * <p>The invariants that make the group's UNION of quantities meaningful are
 * all here (SPEC odluka 14): members share the product (the composite FK
 * already refuses the rest), the binding set, {@code units_per_product} and
 * {@code sort_order}; exactly one ACTIVE default per active group (a radio:
 * setting one clears the rest); an archived default hands the radio to
 * somebody first. Changing the default never touches historical calculations —
 * manufacturing times and payroll read snapshots.
 */
@Service
@RequiredArgsConstructor
public class OperationGroupService {

    private final OperationGroupRepository groups;
    private final OperationRepository operations;
    private final ProductRepository products;

    @Transactional(readOnly = true)
    public List<OperationGroupDto> listForProduct(Long productId) {
        requireProduct(productId);
        return groups.findByProduct_IdOrderByNameAsc(productId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public OperationGroupDto create(OperationGroupCreateRequest request) {
        Product product = requireProduct(request.getProductId());
        String name = request.getName().trim();
        if (groups.nameTakenInProductByAnother(product.getId(), name, null)) {
            throw new ConflictException("Grupa sa tim nazivom već postoji na ovom proizvodu.");
        }
        OperationGroup group = OperationGroup.builder()
                .product(product)
                .name(name)
                .note(blankToNull(request.getNote()))
                .build();
        return toDto(groups.save(group));
    }

    @Transactional
    public OperationGroupDto update(Long id, OperationGroupUpdateRequest request) {
        OperationGroup group = load(id);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv grupe je obavezan.");
            }
            if (groups.nameTakenInProductByAnother(group.getProduct().getId(), name, id)) {
                throw new ConflictException("Grupa sa tim nazivom već postoji na ovom proizvodu.");
            }
            group.setName(name);
        }
        if (request.getNote() != null) {
            group.setNote(blankToNull(request.getNote()));
        }
        return toDto(groups.save(group));
    }

    /** Only an EMPTY group archives: members first leave, out loud. */
    @Transactional
    public void archive(Long id) {
        OperationGroup group = load(id);
        if (!operations.findByOperationGroup_Id(id).isEmpty()) {
            throw new ConflictException("Grupa ima članove — prvo ih izvadi, pa je arhiviraj.");
        }
        group.archive();
        groups.save(group);
    }

    @Transactional
    public void restore(Long id) {
        OperationGroup group = load(id);
        if (groups.nameTakenInProductByAnother(group.getProduct().getId(), group.getName(), id)) {
            throw new ConflictException("Živa grupa sa tim nazivom već postoji — ova ostaje arhivirana.");
        }
        group.restore();
        groups.save(group);
    }

    /**
     * Adds an operation to the group. The first member sets the profile; every
     * later one must match it — same units_per_product, same sort_order, same
     * binding set — or the union of their quantities would mean nothing.
     * The first member also becomes the default, so an active group is never
     * without one.
     */
    @Transactional
    public OperationGroupDto addMember(Long groupId, Long operationId) {
        OperationGroup group = load(groupId);
        if (group.isArchived()) {
            throw new ConflictException("Arhiviranoj grupi se ne dodaju članovi.");
        }
        Operation operation = loadOperation(operationId);
        if (operation.getProduct() == null) {
            throw new ConflictException("Osnovna operacija ne može u grupu.");
        }
        if (!operation.getProduct().getId().equals(group.getProduct().getId())) {
            throw new ConflictException("Operacija ne pripada proizvodu ove grupe.");
        }
        if (operation.getOperationGroup() != null) {
            if (operation.getOperationGroup().getId().equals(groupId)) {
                return toDto(group);
            }
            throw new ConflictException("Operacija je već u grupi „"
                    + operation.getOperationGroup().getName() + "“.");
        }
        if (operation.isArchived()) {
            throw new ConflictException("Arhivirana operacija ne ulazi u grupu.");
        }

        List<Operation> members = operations.findByOperationGroup_Id(groupId);
        if (members.isEmpty()) {
            operation.setOperationGroup(group);
            operation.setGroupDefault(true);
        } else {
            Operation profile = members.get(0);
            requireMatchesProfile(operation, profile);
            operation.setOperationGroup(group);
        }
        operations.save(operation);
        return toDto(group);
    }

    /**
     * Takes an operation out. The default leaves last: while others remain,
     * the radio must first be handed over, so the group never stands without
     * an active default.
     */
    @Transactional
    public OperationGroupDto removeMember(Long groupId, Long operationId) {
        OperationGroup group = load(groupId);
        Operation operation = loadOperation(operationId);
        if (operation.getOperationGroup() == null
                || !operation.getOperationGroup().getId().equals(groupId)) {
            return toDto(group);
        }
        List<Operation> members = operations.findByOperationGroup_Id(groupId);
        if (operation.isGroupDefault() && members.size() > 1) {
            throw new ConflictException(
                    "Ova operacija je default grupe — prvo postavi drugi default, pa je izvadi.");
        }
        operation.setOperationGroup(null);
        operation.setGroupDefault(false);
        operations.save(operation);
        return toDto(group);
    }

    /** The radio: exactly one active default per group. */
    @Transactional
    public OperationGroupDto setDefault(Long groupId, Long operationId) {
        OperationGroup group = load(groupId);
        List<Operation> members = operations.findByOperationGroup_Id(groupId);
        Operation target = members.stream()
                .filter(m -> m.getId().equals(operationId))
                .findFirst()
                .orElseThrow(() -> new ConflictException("Operacija nije član ove grupe."));
        if (target.isArchived()) {
            throw new ConflictException("Arhivirana operacija ne može biti default grupe.");
        }
        for (Operation member : members) {
            boolean shouldBe = member.getId().equals(operationId);
            if (member.isGroupDefault() != shouldBe) {
                member.setGroupDefault(shouldBe);
                operations.save(member);
            }
        }
        return toDto(group);
    }

    // ── invariants ───────────────────────────────────────────────────────────

    private static void requireMatchesProfile(Operation candidate, Operation profile) {
        if (!Objects.equals(candidate.getUnitsPerProduct(), profile.getUnitsPerProduct())) {
            throw new ConflictException(
                    "Članovi grupe moraju imati isti broj komada po proizvodu (KP) — unija učinka inače ne znači ništa.");
        }
        if (!Objects.equals(candidate.getSortOrder(), profile.getSortOrder())) {
            throw new ConflictException("Članovi grupe moraju biti isti korak (isti redosled).");
        }
        Set<Long> candidateBinding = bindingOptionIds(candidate);
        Set<Long> profileBinding = bindingOptionIds(profile);
        if (!candidateBinding.equals(profileBinding)) {
            throw new ConflictException(
                    "Članovi grupe moraju imati isti binding (istu vrednost atributa).");
        }
    }

    private static Set<Long> bindingOptionIds(Operation operation) {
        return operation.getVariantBindings().stream()
                .map(b -> b.getOption().getId())
                .collect(Collectors.toSet());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private OperationGroup load(Long id) {
        return groups.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Grupa operacija nije pronađena: " + id));
    }

    private Operation loadOperation(Long id) {
        return operations.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operacija nije pronađena: " + id));
    }

    private Product requireProduct(Long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Proizvod nije pronađen: " + productId));
    }

    private OperationGroupDto toDto(OperationGroup group) {
        List<OperationGroupMemberDto> members = operations.findByOperationGroup_Id(group.getId())
                .stream()
                .sorted(Comparator.comparing(Operation::getId))
                .map(m -> OperationGroupMemberDto.builder()
                        .operationId(m.getId())
                        .operationName(m.getOpName())
                        .minNorm(m.getMinNorm())
                        .maxNorm(m.getMaxNorm())
                        .unitsPerProduct(m.getUnitsPerProduct())
                        .sortOrder(m.getSortOrder())
                        .groupDefault(m.isGroupDefault())
                        .archived(m.isArchived())
                        .build())
                .toList();
        return OperationGroupDto.builder()
                .id(group.getId())
                .productId(group.getProduct().getId())
                .name(group.getName())
                .note(group.getNote())
                .active(group.getIsActive())
                .archived(group.isArchived())
                .members(members)
                .build();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
