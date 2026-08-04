package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PayrollAdjustmentCategoryService {

    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;
    private final PayrollAdjustmentCategoryTranslationRepository translationRepository;
    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
    private final CompensationSchemeRepository compensationSchemeRepository;
    private final PayrollAdjustmentCategorySchemeRuleRepository ruleRepository;

    @Transactional(readOnly = true)
    public List<PayrollAdjustmentCategoryResponse> findAll() {
        Map<Long, String> englishNames = englishNames();
        return payrollAdjustmentCategoryRepository.findAll()
                .stream()
                .map(c -> new PayrollAdjustmentCategoryResponse(c, englishNames.get(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayrollAdjustmentCategoryResponse findById(Long id) {
        PayrollAdjustmentCategory entity = payrollAdjustmentCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustmentCategory not found"));
        return new PayrollAdjustmentCategoryResponse(entity, englishNameOf(id));
    }

    @Transactional
    public PayrollAdjustmentCategoryResponse create(PayrollAdjustmentCategoryCreateRequest request) {
        PayrollAdjustmentCategory entity = new PayrollAdjustmentCategory();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setSectionCode(request.getSectionCode());
        entity.setSectionOrder(request.getSectionOrder());
        entity.setSortOrder(request.getSortOrder());
        entity.setImpactCode(request.getImpactCode());
        entity.setInputType(request.getInputType());
        entity.setIsManual(request.getIsManual() != null ? request.getIsManual() : false);
        entity.setAllowOverride(request.getAllowOverride() != null ? request.getAllowOverride() : false);
        entity.setOverrideTarget(request.getOverrideTarget());
        entity.setAllowNegative(request.getAllowNegative() != null ? request.getAllowNegative() : false);
        // A NEW CATEGORY IS BORN INACTIVE unless somebody insists otherwise, and
        // insisting is refused below until every active scheme has a rule for it.
        entity.setIsActive(request.getIsActive() != null ? request.getIsActive() : false);
        entity.setVisibleInUi(request.getVisibleInUi() != null ? request.getVisibleInUi() : true);
        entity.setVisibleInPdf(request.getVisibleInPdf() != null ? request.getVisibleInPdf() : true);
        entity.setCalculationKey(request.getCalculationKey());
        entity.setCreatedAt(OffsetDateTime.now());
        refuseActivationWithAGap(entity, false);
        PayrollAdjustmentCategory saved = payrollAdjustmentCategoryRepository.save(entity);
        applyEnglishName(saved, request.getNameEn());
        return new PayrollAdjustmentCategoryResponse(saved, englishNameOf(saved.getId()));
    }

    @Transactional
    public PayrollAdjustmentCategoryResponse update(Long id, PayrollAdjustmentCategoryCreateRequest request) {
        PayrollAdjustmentCategory entity = payrollAdjustmentCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustmentCategory not found"));
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setSectionCode(request.getSectionCode());
        entity.setSectionOrder(request.getSectionOrder());
        entity.setSortOrder(request.getSortOrder());
        entity.setImpactCode(request.getImpactCode());
        entity.setInputType(request.getInputType());
        if (request.getIsManual() != null)      entity.setIsManual(request.getIsManual());
        if (request.getAllowOverride() != null)  entity.setAllowOverride(request.getAllowOverride());
        entity.setOverrideTarget(request.getOverrideTarget());
        if (request.getAllowNegative() != null)  entity.setAllowNegative(request.getAllowNegative());
        boolean wasActive = Boolean.TRUE.equals(entity.getIsActive());
        if (request.getIsActive() != null)       entity.setIsActive(request.getIsActive());
        if (request.getVisibleInUi() != null)    entity.setVisibleInUi(request.getVisibleInUi());
        if (request.getVisibleInPdf() != null)   entity.setVisibleInPdf(request.getVisibleInPdf());
        entity.setCalculationKey(request.getCalculationKey());
        entity.setUpdatedAt(OffsetDateTime.now());
        refuseActivationWithAGap(entity, wasActive);
        PayrollAdjustmentCategory saved = payrollAdjustmentCategoryRepository.save(entity);
        applyEnglishName(saved, request.getNameEn());
        return new PayrollAdjustmentCategoryResponse(saved, englishNameOf(saved.getId()));
    }

    /**
     * A category may not be activated until every active scheme says what to do
     * with it.
     *
     * <p>WHY REFUSE RATHER THAN WARN. A missing rule is not "no restriction": the
     * resolver throws rather than guess, so an active category with a gap stops
     * the payroll of every employee on the scheme that lacks the rule. The
     * alternative to refusing here is an exception under somebody's hand on a
     * Friday, naming an employee who did nothing wrong.
     *
     * <p>Only the TRANSITION is checked. A category that is already active and
     * stays active is left alone, so an unrelated edit to a category whose matrix
     * was incomplete before this rule existed does not become impossible to save.
     *
     * <p>The message NAMES the schemes. "Rule missing" is a fact; the list is what
     * makes it a task.
     */
    private void refuseActivationWithAGap(PayrollAdjustmentCategory entity, boolean wasActive) {
        if (!Boolean.TRUE.equals(entity.getIsActive()) || wasActive) {
            return;
        }
        if (entity.getId() == null) {
            // Being created active: there are no rules yet, so unless no active
            // scheme exists at all, there is certainly a gap.
            List<String> schemes = activeSchemeCodesWithoutARuleFor(null);
            if (!schemes.isEmpty()) {
                throw new ConflictException(
                        "Nova stavka ne može odmah da bude aktivna: nema pravilo ni za jedan "
                                + "način obračuna (" + String.join(", ", schemes) + "). Sačuvajte je "
                                + "neaktivnu, dodajte pravila, pa je aktivirajte.");
            }
            return;
        }
        List<String> schemes = activeSchemeCodesWithoutARuleFor(entity.getId());
        if (!schemes.isEmpty()) {
            throw new ConflictException(
                    "Stavka ne može da se aktivira dok nema pravilo za svaki aktivan način "
                            + "obračuna. Nedostaje: " + String.join(", ", schemes) + ".");
        }
    }

    private List<String> activeSchemeCodesWithoutARuleFor(Long categoryId) {
        return compensationSchemeRepository.findAll().stream()
                .filter(scheme -> scheme.getArchivedAt() == null)
                .filter(scheme -> Boolean.TRUE.equals(scheme.getIsActive()))
                .filter(scheme -> categoryId == null || ruleRepository.findAll().stream()
                        .noneMatch(rule -> rule.getArchivedAt() == null
                                && rule.getCompensationScheme().getId().equals(scheme.getId())
                                && rule.getPayrollAdjustmentCategory().getId().equals(categoryId)))
                .map(scheme -> scheme.getCode())
                .sorted()
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollAdjustmentCategoryRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollAdjustmentCategory not found");
        }
        // The FK from payroll_adjustments is ON DELETE RESTRICT, so a category
        // with history cannot be deleted — deleting it used to CASCADE and take
        // the historical adjustments with it, including ones on locked payroll
        // runs. Checked here so the caller gets a clear 409 explaining what to do
        // instead of a raw constraint violation.
        if (payrollAdjustmentRepository.existsByPayrollAdjustmentCategory_Id(id)) {
            throw new ConflictException(
                    "Kategorija se koristi u postojećim obračunima i ne može se obrisati. "
                            + "Deaktivirajte je umesto brisanja.");
        }
        payrollAdjustmentCategoryRepository.deleteById(id);
    }

    // ── English name, stored in the translation table ───────────────────────
    //
    // sr-Latn is NOT stored here: it is served from
    // payroll_adjustment_categories.name, so there is one place to edit the
    // Serbian name and nothing to drift out of sync. Only the English override
    // needs a row.

    private Map<Long, String> englishNames() {
        Map<Long, String> byCategoryId = new HashMap<>();
        translationRepository.findAllByLocale(AppLocales.ENGLISH).forEach(t ->
                byCategoryId.put(t.getPayrollAdjustmentCategory().getId(), t.getName()));
        return byCategoryId;
    }

    private String englishNameOf(Long categoryId) {
        return translationRepository.findByCategoryAndLocale(categoryId, AppLocales.ENGLISH)
                .map(PayrollAdjustmentCategoryTranslation::getName)
                .orElse(null);
    }

    /**
     * Upsert or remove the English name.
     *
     * <p>A blank value removes the row rather than storing an empty string, so
     * the name falls back to the master name instead of rendering as a gap on a
     * payslip. {@code null} leaves any existing translation alone, which lets a
     * client that does not know about translations update the other fields
     * without wiping them.
     */
    private void applyEnglishName(PayrollAdjustmentCategory category, String nameEn) {
        if (nameEn == null) {
            return;
        }
        var existing = translationRepository.findByCategoryAndLocale(category.getId(), AppLocales.ENGLISH);
        if (nameEn.isBlank()) {
            existing.ifPresent(translationRepository::delete);
            return;
        }
        PayrollAdjustmentCategoryTranslation translation = existing.orElseGet(() ->
                PayrollAdjustmentCategoryTranslation.builder()
                        .payrollAdjustmentCategory(category)
                        .locale(AppLocales.ENGLISH)
                        .build());
        translation.setName(nameEn.trim());
        translationRepository.save(translation);
    }
}
