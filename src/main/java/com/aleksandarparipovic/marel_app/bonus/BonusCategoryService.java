package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.bonus.dto.BonusAmountPeriodDto;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryDto;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryOptionDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BonusCategoryService {

    private final BonusCategoryRepository repository;
    private final BonusCategoryAmountPeriodRepository amountPeriodRepository;
    private final BonusCategoryMapper mapper;
    private final com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService passwordConfirmation;
    private final com.aleksandarparipovic.marel_app.auth.CurrentUserService currentUserService;


    /**
     * {@code includeArchived} exists for the admin catalogue screen, which lists
     * archived categories so they can be restored. Every other caller keeps the
     * old behaviour: an archived category is simply gone from the answer.
     */
    List<BonusCategoryDto> search(Boolean active,
                                  String code,
                                  LocalDate validOn,
                                  boolean includeArchived){
        // Specification.where(null) is refused by this Spring Data version, so
        // the base is the empty allOf() and notArchived joins conditionally.
        Specification<BonusCategory> spec = Specification.allOf();
        if (!includeArchived) {
            spec = spec.and(BonusCategorySpecifications.notArchived());
        }
        spec = spec
                .and(BonusCategorySpecifications.isActive(active))
                .and(BonusCategorySpecifications.hasCode(code))
                .and(BonusCategorySpecifications.validOn(validOn));

        return repository.findAll(spec)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BonusCategoryOptionDto> getAllActiveBonusCategories(){
        return repository.findByActiveTrueOrderByCategoryNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BonusCategoryOptionDto> getActiveAndValidBonusCategories() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        return repository.findActiveAndValid(today)
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional
    public BonusCategory create(BonusCategory cat) {
        cat.setId(null);
        requireValidDates(cat);
        BonusCategory saved = repository.save(cat);
        // The amount is a dated fact from day one (V69): the first spell opens
        // with the category and is what the payroll months read.
        amountPeriodRepository.save(BonusCategoryAmountPeriod.builder()
                .bonusCategory(saved)
                .amount(saved.getBonusAmount())
                .validFrom(saved.getValidFrom())
                .createdBy(currentUserService.getCurrentUserId())
                .build());
        return saved;
    }

    /** The category's amount history, newest first — the šifarnik's detail view. */
    @Transactional(readOnly = true)
    public List<BonusAmountPeriodDto> amountHistory(Long categoryId) {
        return amountPeriodRepository.findHistoryFor(categoryId).stream()
                .map(BonusAmountPeriodDto::from)
                .toList();
    }

    /**
     * Edits the fields the form offers. Deliberately NOT touched here:
     * {@code minHours} (legacy logic the form no longer shows — an edit must not
     * silently wipe a recorded threshold) and {@code active} (archiving and
     * restoring own that flag, not the edit form).
     *
     * <p>A CHANGED AMOUNT is versioned (V69), never edited in place: the open
     * spell closes the day before {@code amountEffectiveFrom} (empty = today)
     * and a new one opens — the same transition the shift periods make. The
     * same date as the open spell's start is a CORRECTION of that spell.
     * {@code bonus_categories.bonus_amount} then mirrors whatever spell is in
     * force TODAY, so lists keep reading one column.
     */
    @Transactional
    public BonusCategory update(Long id, BonusCategory updated, LocalDate amountEffectiveFrom) {
        BonusCategory existing = repository.findById(id)
                .orElseThrow();

        existing.setCategoryNo(updated.getCategoryNo());
        existing.setCategoryName(updated.getCategoryName());
        existing.setDescription(updated.getDescription());
        existing.setValidFrom(updated.getValidFrom());
        existing.setValidUntil(updated.getValidUntil());
        requireValidDates(existing);

        versionAmount(existing, updated.getBonusAmount(), amountEffectiveFrom);

        return repository.save(existing);
    }

    /** Opens/corrects the amount spell and refreshes the category's mirror column. */
    private void versionAmount(BonusCategory category, BigDecimal newAmount, LocalDate effectiveFrom) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        BonusCategoryAmountPeriod open = amountPeriodRepository.findOpenFor(category.getId()).orElse(null);

        if (open == null) {
            // A category from before the V69 seed replay — open its first spell.
            amountPeriodRepository.saveAndFlush(BonusCategoryAmountPeriod.builder()
                    .bonusCategory(category)
                    .amount(newAmount)
                    .validFrom(effectiveFrom != null ? effectiveFrom : today)
                    .createdBy(currentUserService.getCurrentUserId())
                    .build());
        } else if (open.getAmount().compareTo(newAmount) != 0) {
            LocalDate from = effectiveFrom != null ? effectiveFrom : today;
            if (from.equals(open.getValidFrom())) {
                // The same start date is a CORRECTION — no second spell opens.
                open.setAmount(newAmount);
                amountPeriodRepository.saveAndFlush(open);
            } else if (from.isAfter(open.getValidFrom())) {
                open.setValidTo(from.minusDays(1));
                amountPeriodRepository.saveAndFlush(open);
                amountPeriodRepository.saveAndFlush(BonusCategoryAmountPeriod.builder()
                        .bonusCategory(category)
                        .amount(newAmount)
                        .validFrom(from)
                        .createdBy(currentUserService.getCurrentUserId())
                        .build());
            } else {
                throw new IllegalArgumentException(
                        "Novi iznos mora da važi od datuma posle početka trenutnog iznosa ("
                                + open.getValidFrom() + ").");
            }
        }

        // The mirror: whatever spell covers TODAY (a future-dated change leaves
        // today's amount on the column until its day comes).
        amountPeriodRepository.findInForce(category.getId(), today)
                .ifPresentOrElse(
                        p -> category.setBonusAmount(p.getAmount()),
                        () -> category.setBonusAmount(newAmount));
    }

    /** Archive, signed with the caller's re-typed password. */
    @Transactional
    public void archive(Long id, String password, org.springframework.security.core.Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        BonusCategory c = repository.findById(id).orElseThrow();
        c.setArchivedAt(OffsetDateTime.now());
        c.setActive(false);
        repository.save(c);
    }

    @Transactional
    public void restore(Long id) {
        BonusCategory c = repository.findById(id).orElseThrow();
        c.setArchivedAt(null);
        c.setActive(true);
        repository.save(c);
    }

    private static void requireValidDates(BonusCategory cat) {
        if (cat.getValidUntil() != null && cat.getValidUntil().isBefore(cat.getValidFrom())) {
            throw new IllegalArgumentException("Datum „važi do“ ne može biti pre datuma „važi od“.");
        }
    }
}
