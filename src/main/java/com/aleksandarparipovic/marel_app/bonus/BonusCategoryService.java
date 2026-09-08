package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryDto;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryOptionDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BonusCategoryService {

    private final BonusCategoryRepository repository;
    private final BonusCategoryMapper mapper;
    private final com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService passwordConfirmation;


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
        return repository.save(cat);
    }

    /**
     * Edits the fields the form offers. Deliberately NOT touched here:
     * {@code minHours} (legacy logic the form no longer shows — an edit must not
     * silently wipe a recorded threshold) and {@code active} (archiving and
     * restoring own that flag, not the edit form).
     */
    @Transactional
    public BonusCategory update(Long id, BonusCategory updated) {
        BonusCategory existing = repository.findById(id)
                .orElseThrow();

        existing.setCategoryNo(updated.getCategoryNo());
        existing.setCategoryName(updated.getCategoryName());
        existing.setBonusAmount(updated.getBonusAmount());
        existing.setDescription(updated.getDescription());
        existing.setValidFrom(updated.getValidFrom());
        existing.setValidUntil(updated.getValidUntil());
        requireValidDates(existing);

        return repository.save(existing);
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
