package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryNameResolver;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryTranslation;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryTranslationRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryNameResolver;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryTranslation;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryTranslationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-backed translation behaviour for the two master reference tables that
 * have one.
 *
 * <p>The point being protected throughout is the fallback contract: a missing
 * translation must yield the default name, never null and never a blank label on
 * a payslip.
 */
@Transactional
class CategoryTranslationIT extends AbstractIntegrationTest {

    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private WorkCodeCategoryTranslationRepository categoryTranslationRepository;
    @Autowired private WorkCodeCategoryNameResolver categoryNameResolver;
    @Autowired private PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;
    @Autowired private PayrollAdjustmentCategoryTranslationRepository adjustmentTranslationRepository;
    @Autowired private PayrollAdjustmentCategoryNameResolver adjustmentNameResolver;
    @Autowired private EntityManager entityManager;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private WorkCodeCategory category(String serbianName) {
        int n = COUNTER.incrementAndGet();
        return categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-TR-" + n)
                .categoryName(serbianName)
                .type("WORK")
                .isPaid(true)
                .normMultiplier(1.0d)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(true)
                .allowsParallelWork(false)
                .displayOrder(0)
                .baseCategory(false)
                .build());
    }

    private void translate(WorkCodeCategory category, String locale, String name) {
        categoryTranslationRepository.saveAndFlush(WorkCodeCategoryTranslation.builder()
                .workCodeCategory(category)
                .locale(locale)
                .name(name)
                .build());
    }

    private PayrollAdjustmentCategory adjustmentCategory(String serbianName) {
        int n = COUNTER.incrementAndGet();
        PayrollAdjustmentCategory category = new PayrollAdjustmentCategory();
        category.setCode("IT-ADJ-" + n);
        category.setName(serbianName);
        category.setSectionCode("ADDITIONS");
        category.setSectionOrder(0);
        category.setSortOrder(0);
        category.setImpactCode("GROSS_PLUS");
        category.setIsManual(true);
        category.setAllowOverride(false);
        category.setOverrideTarget("AMOUNT");
        category.setAllowNegative(false);
        category.setIsActive(true);
        category.setVisibleInUi(true);
        category.setVisibleInPdf(true);
        category.setShowName(true);
        category.setCreatedAt(OffsetDateTime.now());
        return adjustmentCategoryRepository.saveAndFlush(category);
    }

    // ── work-code categories ────────────────────────────────────────────────

    @Test
    @DisplayName("English returns the translation; the default locale returns the master name")
    void englishTranslationIsReturnedAndDefaultFallsBack() {
        WorkCodeCategory category = category("Zavarivanje");
        translate(category, AppLocales.ENGLISH, "Welding");

        Map<Long, String> english = categoryNameResolver.translationsFor(AppLocales.ENGLISH);
        assertThat(categoryNameResolver.displayName(category, english)).isEqualTo("Welding");

        // sr-Latn is deliberately not seeded — it is served from category_name so
        // there is one place to edit the Serbian name.
        Map<Long, String> serbian = categoryNameResolver.translationsFor(AppLocales.DEFAULT);
        assertThat(categoryNameResolver.displayName(category, serbian)).isEqualTo("Zavarivanje");
    }

    @Test
    @DisplayName("a missing English translation falls back to the default name rather than null")
    void missingTranslationFallsBack() {
        WorkCodeCategory category = category("Galvanizacija");

        Map<Long, String> english = categoryNameResolver.translationsFor(AppLocales.ENGLISH);

        assertThat(categoryNameResolver.displayName(category, english)).isEqualTo("Galvanizacija");
    }

    @Test
    @DisplayName("an unknown or blank locale falls back to the default rather than failing")
    void unknownLocaleFallsBackToDefault() {
        assertThat(AppLocales.normalize("de")).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.normalize(null)).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.normalize("  ")).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.normalize("EN")).isEqualTo(AppLocales.ENGLISH);
    }

    @Test
    @DisplayName("the same category and locale cannot be translated twice, case-insensitively")
    void duplicateCategoryLocaleIsRejected() {
        WorkCodeCategory category = category("Livac");
        translate(category, AppLocales.ENGLISH, "Caster");

        assertThatThrownBy(() -> translate(category, "EN", "Foundry worker"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // The two blank checks are separate tests on purpose: a constraint violation
    // aborts the PostgreSQL transaction, so a second write in the same test would
    // fail with "current transaction is aborted" rather than the constraint being
    // asserted.

    @Test
    @DisplayName("a blank locale is rejected by the database")
    void blankLocaleIsRejected() {
        WorkCodeCategory category = category("Plastika");

        assertThatThrownBy(() -> translate(category, "   ", "Plastics"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a blank translated name is rejected by the database")
    void blankTranslatedNameIsRejected() {
        WorkCodeCategory category = category("Plastika 2");

        assertThatThrownBy(() -> translate(category, AppLocales.ENGLISH, "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("translating a category never changes its code")
    void codeIsNotTranslated() {
        WorkCodeCategory category = category("Bolovanje");
        String codeBefore = category.getCategoryNo();
        translate(category, AppLocales.ENGLISH, "Sick leave");
        entityManager.clear();

        assertThat(categoryRepository.findById(category.getId()))
                .get()
                .extracting(WorkCodeCategory::getCategoryNo)
                .isEqualTo(codeBefore);
    }

    @Test
    @DisplayName("the seeded English names are present for the categories the migration knows")
    void seededEnglishNameExistsForTheCommonCategory() {
        WorkCodeCategory allShifts = categoryRepository.findAll().stream()
                .filter(c -> "S".equalsIgnoreCase(c.getCategoryNo())
                        || "FOREIGN_ALL_SHIFTS".equalsIgnoreCase(c.getCategoryNo()))
                .findFirst().orElseThrow();

        Map<Long, String> english = categoryNameResolver.translationsFor(AppLocales.ENGLISH);

        assertThat(categoryNameResolver.displayName(allShifts, english))
                .isEqualTo("1st, 2nd and 3rd shift");
        assertThat(allShifts.getCategoryName())
                .as("the Serbian name is untouched by the translation")
                .isEqualTo("I, II i III smena");
    }

    // ── payroll adjustment categories ───────────────────────────────────────

    @Test
    @DisplayName("an adjustment category resolves its English name and falls back without one")
    void adjustmentTranslationAndFallback() {
        PayrollAdjustmentCategory translated = adjustmentCategory("Topli obrok");
        PayrollAdjustmentCategory untranslated = adjustmentCategory("Nešto drugo");
        adjustmentTranslationRepository.saveAndFlush(PayrollAdjustmentCategoryTranslation.builder()
                .payrollAdjustmentCategory(translated)
                .locale(AppLocales.ENGLISH)
                .name("Meal allowance")
                .build());

        Map<Long, String> english = adjustmentNameResolver.translationsFor(AppLocales.ENGLISH);

        assertThat(adjustmentNameResolver.displayName(translated.getId(), translated.getName(), english))
                .isEqualTo("Meal allowance");
        assertThat(adjustmentNameResolver.displayName(untranslated.getId(), untranslated.getName(), english))
                .isEqualTo("Nešto drugo");
    }

    @Test
    @DisplayName("payroll_adjustments has no translated-name column of its own")
    void transactionalTableCarriesNoTranslatedName() {
        @SuppressWarnings("unchecked")
        java.util.List<String> columns = entityManager.createNativeQuery("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'payroll_adjustments'
                        """)
                .getResultList();

        // A translated name belongs to the master category and is resolved
        // through payroll_adjustment_category_id. Copying it onto every
        // transactional row would guarantee they diverge on the first typo fix.
        assertThat(columns).doesNotContain(
                "name", "name_en", "english_name", "english_transcription",
                "category_name", "category_name_en");
    }

    @Test
    @DisplayName("payroll_run_item_categories has no translated-name column either")
    void payrollRunItemCategoriesCarryNoName() {
        @SuppressWarnings("unchecked")
        java.util.List<String> columns = entityManager.createNativeQuery("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'payroll_run_item_categories'
                        """)
                .getResultList();

        assertThat(columns).doesNotContain(
                "name", "name_en", "english_name", "english_transcription",
                "work_code_category_name", "work_code_category_name_en");
    }

    @Test
    @DisplayName("only the two master tables have a translation table")
    void translationTablesAreScopedToMasterData() {
        @SuppressWarnings("unchecked")
        java.util.List<String> tables = entityManager.createNativeQuery("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_name like '%_translations'
                        order by table_name
                        """)
                .getResultList();

        assertThat(tables).containsExactly(
                "payroll_adjustment_category_translations",
                "work_code_category_translations");
    }
}
