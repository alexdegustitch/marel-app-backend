package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryAdminService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryService;
import com.aleksandarparipovic.marel_app.work_code.dto.ReorderWorkCodeCategoriesRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.UpsertWorkCodeCategoryRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDetailDto;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDto;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategorySchemeRuleFormDto;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The šifarnik's create / edit / reorder of work-code categories, against the
 * real schema.
 *
 * <p>A database is required because most of what is asserted IS the database:
 * the GiST exclusion constraint against overlapping versions of one code (and
 * the V48 removal of the code+name unique index that used to forbid
 * re-versioning), the flush ordering that closes a version before its
 * successor is inserted, and the date-window repository queries the derived
 * pairs and scheme rules live behind.
 */
@Transactional
class WorkCodeCategoryAdminIT extends AbstractIntegrationTest {

    @Autowired private WorkCodeCategoryAdminService adminService;
    @Autowired private WorkCodeCategoryService categoryService;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private WorkCodeCategoryMappingRepository mappingRepository;
    @Autowired private WorkCodeCategorySchemeRuleRepository ruleRepository;
    @Autowired private CompensationSchemeRepository schemeRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);

    // ── fixtures ────────────────────────────────────────────────────────────

    private String freshCode() {
        return "IT" + COUNTER.incrementAndGet();
    }

    private UpsertWorkCodeCategoryRequest request(String code, LocalDate validFrom) {
        return new UpsertWorkCodeCategoryRequest(
                code, "Kategorija " + code, "WORK", 1.0, validFrom,
                true, true, null,
                false, null,
                true, true, true, true, false,
                false, false,
                null, null,
                List.of());
    }

    private UpsertWorkCodeCategoryRequest withPairs(UpsertWorkCodeCategoryRequest r,
                                                    boolean weekend, boolean night) {
        return new UpsertWorkCodeCategoryRequest(
                r.categoryNo(), r.categoryName(), r.type(), r.normMultiplier(), r.validFrom(),
                r.isPaid(), r.affectsNorm(), r.note(), r.fixedHourlyRate(), r.hourlyRate(),
                r.affectsMealAllowance(), r.basicWorkOperation(), r.affectsWeekendBonus(),
                r.affectsMonthlyBonus(), r.isFullDay(), weekend, night,
                r.color(), r.pattern(), r.schemeRules());
    }

    private CompensationScheme scheme(String code) {
        return schemeRepository.findByCode(code).orElseThrow();
    }

    private WorkCodeCategory open(String code) {
        return categoryRepository
                .findFirstByCategoryNoIgnoreCaseAndValidUntilIsNullAndArchivedAtIsNull(code)
                .orElseThrow();
    }

    private Optional<WorkCodeCategoryMapping> openMapping(Long sourceId, String type) {
        return mappingRepository
                .findByMappingTypeInAndValidUntilIsNullAndIsActiveTrueAndArchivedAtIsNull(List.of(type))
                .stream()
                .filter(m -> m.getSourceCategory().getId().equals(sourceId))
                .findFirst();
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create writes the category and appends it to the display order")
    void createPlainCategory() {
        String code = freshCode();
        WorkCodeCategoryAdminDetailDto detail = adminService.create(request(code, FROM));

        WorkCodeCategoryAdminDto dto = detail.category();
        assertThat(dto.no()).isEqualTo(code);
        assertThat(dto.validFrom()).isEqualTo(FROM);
        assertThat(dto.validUntil()).isNull();
        assertThat(dto.editable()).isTrue();

        // Appended after everything already there, in steps of 5.
        int maxOther = adminService.listAll().stream()
                .filter(c -> !c.no().equals(code))
                .mapToInt(WorkCodeCategoryAdminDto::displayOrder).max().orElse(0);
        assertThat(dto.displayOrder()).isGreaterThan(maxOther);
    }

    @Test
    @DisplayName("the weekend and night checkboxes create the B and 3 pairs with their mappings")
    void createWithBothPairs() {
        String code = freshCode();
        adminService.create(withPairs(request(code, FROM), true, true));

        WorkCodeCategory base = open(code);
        WorkCodeCategory weekend = open(code + "B");
        WorkCodeCategory night = open(code + "3");

        assertThat(weekend.getCategoryName()).isEqualTo("Kategorija " + code + " bonus");
        assertThat(night.getCategoryName()).isEqualTo("Kategorija " + code + " noćna smena");
        assertThat(weekend.getValidFrom()).isEqualTo(FROM);
        assertThat(weekend.getNormMultiplier()).isEqualTo(base.getNormMultiplier());

        WorkCodeCategoryMapping weekendMapping = openMapping(base.getId(), "WEEKEND_BONUS").orElseThrow();
        assertThat(weekendMapping.getTargetCategory().getId()).isEqualTo(weekend.getId());
        assertThat(weekendMapping.getValidFrom()).isEqualTo(FROM);
        WorkCodeCategoryMapping nightMapping = openMapping(base.getId(), "NIGHT_SHIFT_BONUS").orElseThrow();
        assertThat(nightMapping.getTargetCategory().getId()).isEqualTo(night.getId());

        // A derived pair is listed but edited through its base.
        WorkCodeCategoryAdminDto weekendDto = adminService.listAll().stream()
                .filter(c -> c.id().equals(weekend.getId())).findFirst().orElseThrow();
        assertThat(weekendDto.editable()).isFalse();
        assertThat(weekendDto.derivedFromCategoryId()).isEqualTo(base.getId());
        assertThatThrownBy(() -> adminService.update(weekend.getId(), request(code + "B", FROM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("izvedena");
    }

    @Test
    @DisplayName("a closed scheme always gets its decision written; an open one only a deviation")
    void createWritesSchemeRules() {
        CompensationScheme standard = scheme(CompensationSchemeCodes.STANDARD);
        CompensationScheme foreign = scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);

        String code = freshCode();
        UpsertWorkCodeCategoryRequest base = request(code, FROM);
        UpsertWorkCodeCategoryRequest withRules = new UpsertWorkCodeCategoryRequest(
                base.categoryNo(), base.categoryName(), base.type(), base.normMultiplier(),
                base.validFrom(), base.isPaid(), base.affectsNorm(), base.note(),
                base.fixedHourlyRate(), base.hourlyRate(), base.affectsMealAllowance(),
                base.basicWorkOperation(), base.affectsWeekendBonus(), base.affectsMonthlyBonus(),
                base.isFullDay(), true, false,
                null, null,
                List.of(
                        new UpsertWorkCodeCategoryRequest.SchemeRuleInput(
                                standard.getId(), null, null, true, true, null),
                        new UpsertWorkCodeCategoryRequest.SchemeRuleInput(
                                foreign.getId(), null, new BigDecimal("1.00"), true, true, "fiksno")));

        adminService.create(withRules);
        WorkCodeCategory created = open(code);
        WorkCodeCategory pair = open(code + "B");

        // STANDARD at its defaults writes nothing.
        assertThat(ruleRepository.findActiveForSourceCategoryAt(created.getId(), FROM).stream()
                .filter(r -> r.getCompensationScheme().getId().equals(standard.getId())))
                .isEmpty();

        // The closed scheme's decision is written — for the base AND the pair,
        // because the mapping's target needs an answer too.
        WorkCodeCategorySchemeRule baseRule = ruleRepository
                .findActiveForSourceCategoryAt(created.getId(), FROM).stream()
                .filter(r -> r.getCompensationScheme().getId().equals(foreign.getId()))
                .findFirst().orElseThrow();
        assertThat(baseRule.getCoefficientOverride()).isEqualByComparingTo("1.00");
        assertThat(baseRule.getIsAllowed()).isTrue();
        assertThat(ruleRepository.findActiveForSourceCategoryAt(pair.getId(), FROM).stream()
                .filter(r -> r.getCompensationScheme().getId().equals(foreign.getId()))
                .findFirst().orElseThrow()
                .getCoefficientOverride()).isEqualByComparingTo("1.00");
    }

    // ── edit: correction vs new version ─────────────────────────────────────

    @Test
    @DisplayName("the same 'važi od' corrects the version in place")
    void sameDateCorrectsInPlace() {
        String code = freshCode();
        Long id = adminService.create(request(code, FROM)).category().id();

        UpsertWorkCodeCategoryRequest corrected = new UpsertWorkCodeCategoryRequest(
                code, "Ispravljen naziv", "WORK", 1.2, FROM,
                true, true, "beleška", false, null,
                true, true, true, true, false, false, false, null, null, List.of());
        WorkCodeCategoryAdminDetailDto result = adminService.update(id, corrected);

        assertThat(result.category().id()).isEqualTo(id);
        assertThat(result.category().normMultiplier()).isEqualTo(1.2);
        assertThat(result.category().name()).isEqualTo("Ispravljen naziv");
        assertThat(categoryRepository.findByCategoryNoIgnoreCaseAndArchivedAtIsNull(code)).hasSize(1);
    }

    @Test
    @DisplayName("a later 'važi od' closes the version and opens a new one; both dates resolve to their own values")
    void laterDateOpensNewVersion() {
        String code = freshCode();
        Long firstId = adminService.create(withPairs(request(code, FROM), true, false))
                .category().id();
        WorkCodeCategory firstPair = open(code + "B");

        LocalDate cutover = FROM.plusMonths(1);
        UpsertWorkCodeCategoryRequest change = new UpsertWorkCodeCategoryRequest(
                code, "Kategorija " + code, "WORK", 1.5, cutover,
                true, true, null, false, null,
                true, true, true, true, false, true, false, null, null, List.of());
        WorkCodeCategoryAdminDetailDto result = adminService.update(firstId, change);

        Long secondId = result.category().id();
        assertThat(secondId).isNotEqualTo(firstId);

        WorkCodeCategory first = categoryRepository.findById(firstId).orElseThrow();
        assertThat(first.getValidUntil()).isEqualTo(cutover.minusDays(1));

        // The repository's own date-window answer — the thing recalc trusts.
        assertThat(categoryRepository.findInForceByCategoryNo(code, cutover.minusDays(5))
                .orElseThrow().getNormMultiplier()).isEqualTo(1.0);
        assertThat(categoryRepository.findInForceByCategoryNo(code, cutover.plusDays(5))
                .orElseThrow().getNormMultiplier()).isEqualTo(1.5);

        // The pair re-versioned with its base, and the mapping follows the ids.
        WorkCodeCategory secondPair = open(code + "B");
        assertThat(secondPair.getId()).isNotEqualTo(firstPair.getId());
        assertThat(secondPair.getNormMultiplier()).isEqualTo(1.5);
        assertThat(categoryRepository.findById(firstPair.getId()).orElseThrow().getValidUntil())
                .isEqualTo(cutover.minusDays(1));
        WorkCodeCategoryMapping mapping = openMapping(secondId, "WEEKEND_BONUS").orElseThrow();
        assertThat(mapping.getTargetCategory().getId()).isEqualTo(secondPair.getId());
        assertThat(mapping.getValidFrom()).isEqualTo(cutover);

        // The dropdown offers ONE version of the code — the one in force today.
        long offered = categoryService.getAllWorkCodeCategories(null, false).stream()
                .filter(dto -> dto.no().equals(code))
                .count();
        assertThat(offered).isEqualTo(1);
    }

    @Test
    @DisplayName("an earlier 'važi od' is refused with a clear message")
    void earlierDateIsRefused() {
        String code = freshCode();
        Long id = adminService.create(request(code, FROM)).category().id();

        UpsertWorkCodeCategoryRequest change = new UpsertWorkCodeCategoryRequest(
                code, "Kategorija " + code, "WORK", 2.0, FROM.minusDays(1),
                true, true, null, false, null,
                true, true, true, true, false, false, false, null, null, List.of());
        assertThatThrownBy(() -> adminService.update(id, change))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("važi od");
    }

    @Test
    @DisplayName("switching a pair off closes the pair and its mapping from the given date")
    void togglePairOff() {
        String code = freshCode();
        Long id = adminService.create(withPairs(request(code, FROM), true, false)).category().id();
        WorkCodeCategory pair = open(code + "B");

        LocalDate cutover = FROM.plusMonths(2);
        UpsertWorkCodeCategoryRequest off = new UpsertWorkCodeCategoryRequest(
                code, "Kategorija " + code, "WORK", 1.0, cutover,
                true, true, null, false, null,
                true, true, true, true, false, false, false, null, null, List.of());
        WorkCodeCategoryAdminDetailDto result = adminService.update(id, off);

        assertThat(result.category().hasWeekendBonusPair()).isFalse();
        assertThat(categoryRepository.findById(pair.getId()).orElseThrow().getValidUntil())
                .isEqualTo(cutover.minusDays(1));
        assertThat(openMapping(result.category().id(), "WEEKEND_BONUS")).isEmpty();
        // The pair is closed, never deleted: the old date still resolves it.
        assertThat(categoryRepository.findInForceByCategoryNo(code + "B", cutover.minusDays(5)))
                .isPresent();
    }

    // ── reorder ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drag order writes 5, 10, 15… across every version of a code")
    void reorderWritesSteppedOrder() {
        String a = freshCode();
        String b = freshCode();
        Long idA = adminService.create(request(a, FROM)).category().id();
        Long idB = adminService.create(request(b, FROM)).category().id();

        // Version A so its chain has two rows.
        adminService.update(idA, new UpsertWorkCodeCategoryRequest(
                a, "Kategorija " + a, "WORK", 1.3, FROM.plusMonths(1),
                true, true, null, false, null,
                true, true, true, true, false, false, false, null, null, List.of()));
        Long idA2 = open(a).getId();

        List<Long> order = adminService.listAll().stream()
                .map(WorkCodeCategoryAdminDto::id)
                .filter(x -> !x.equals(idA2) && !x.equals(idB))
                .toList();
        // Put B first, then A's current version, then everything else.
        List<Long> newOrder = new java.util.ArrayList<>(List.of(idB, idA2));
        newOrder.addAll(order);
        adminService.reorder(new ReorderWorkCodeCategoriesRequest(newOrder));

        assertThat(open(b).getDisplayOrder()).isEqualTo(5);
        assertThat(open(a).getDisplayOrder()).isEqualTo(10);
        // The closed version of A carries the same chain value.
        assertThat(categoryRepository.findById(idA).orElseThrow().getDisplayOrder()).isEqualTo(10);
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("clear business errors: fixed rate without a rate, base operation off WORK, duplicate code")
    void validations() {
        String code = freshCode();
        UpsertWorkCodeCategoryRequest fixedWithoutRate = new UpsertWorkCodeCategoryRequest(
                code, "X", "WORK", 1.0, FROM, true, true, null,
                true, null,
                true, true, true, true, false, false, false, null, null, List.of());
        assertThatThrownBy(() -> adminService.create(fixedWithoutRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("satnic");

        UpsertWorkCodeCategoryRequest basicWorkOperationAbsence = new UpsertWorkCodeCategoryRequest(
                code, "X", "ABSENCE", 1.0, FROM, true, true, null,
                false, null,
                true, true, true, true, false, false, false, null, null, List.of());
        assertThatThrownBy(() -> adminService.create(basicWorkOperationAbsence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("na poslu");

        adminService.create(request(code, FROM));
        assertThatThrownBy(() -> adminService.create(request(code, FROM.plusYears(1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(code);
    }

    @Test
    @DisplayName("the detail prefill answers every active scheme, defaults included")
    void detailListsEveryScheme() {
        String code = freshCode();
        Long id = adminService.create(request(code, FROM)).category().id();

        WorkCodeCategoryAdminDetailDto detail = adminService.detail(id);
        List<String> schemeCodes = detail.schemeRules().stream()
                .map(WorkCodeCategorySchemeRuleFormDto::schemeCode)
                .toList();
        assertThat(schemeCodes).contains(
                CompensationSchemeCodes.STANDARD, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);

        WorkCodeCategorySchemeRuleFormDto standard = detail.schemeRules().stream()
                .filter(r -> CompensationSchemeCodes.STANDARD.equals(r.schemeCode()))
                .findFirst().orElseThrow();
        assertThat(standard.hasRule()).isFalse();
        assertThat(standard.isAllowed()).isTrue();

        // The closed scheme's explicit decision (written on create) reads back.
        WorkCodeCategorySchemeRuleFormDto foreign = detail.schemeRules().stream()
                .filter(r -> CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT.equals(r.schemeCode()))
                .findFirst().orElseThrow();
        assertThat(foreign.hasRule()).isTrue();
        assertThat(foreign.isAllowed()).isFalse();
    }
}
