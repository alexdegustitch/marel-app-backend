package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryService;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing may be activated with a gap in the scheme × category matrix.
 *
 * <p>The Phase 5 lifecycle, finally enforced rather than merely reported: a new
 * category is created inactive, gets a rule for every active scheme, and only
 * then may it be activated. Same for a scheme, in the other direction.
 *
 * <p>WHY REFUSE RATHER THAN WARN — the choice the risk table (R6) left open, made
 * on 2026-08-04. A missing rule is not "no restriction": the resolver throws
 * rather than guess, so an active category with a gap stops the payroll of every
 * employee on the scheme that lacks the rule. Warning would mean discovering that
 * on a Friday, under an employee's name. The refusal names what is missing, so it
 * is a task rather than an obstacle.
 *
 * <p>THE TWO HALVES ARE ENFORCED IN DIFFERENT PLACES, and that is not an
 * inconsistency. Categories are created and activated through the application, so
 * the guard is in the service. Schemes are DATA ONLY — the controller is
 * read-only and NewCompensationSchemeIsDataOnlyIT keeps it that way — so a rule
 * about activating one has to live where activation happens, which is SQL.
 */
@Transactional
class PayrollActivationGateIT extends AbstractIntegrationTest {

    @Autowired private PayrollAdjustmentCategoryService categoryService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private PayrollAdjustmentCategoryCreateRequest aCategory(String code, Boolean active) {
        PayrollAdjustmentCategoryCreateRequest request = new PayrollAdjustmentCategoryCreateRequest();
        request.setCode(code);
        request.setName("Nova stavka");
        request.setSectionCode("ADDITIONS");
        request.setSectionOrder(1);
        request.setSortOrder(999);
        request.setImpactCode("GROSS_PLUS");
        request.setInputType("AMOUNT");
        request.setIsManual(true);
        request.setOverrideTarget("AMOUNT");
        request.setIsActive(active);
        return request;
    }

    // ── categories ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a new category is created INACTIVE by default — the matrix cannot be complete yet")
    void aNewCategoryIsInactive() {
        fixture.scenario().build();

        var created = categoryService.create(aCategory("IT_GATE_DEFAULT", null));

        // It used to default to active, which meant every new category shipped
        // with a gap for every scheme and stopped payroll the moment somebody
        // opened a month.
        assertThat(created.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("creating one ACTIVE is refused, and the message names the schemes")
    void creatingAnActiveCategoryIsRefused() {
        fixture.scenario().build();

        assertThatThrownBy(() -> categoryService.create(aCategory("IT_GATE_ACTIVE", true)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("STANDARD");
    }

    @Test
    @DisplayName("activating one later is refused while any active scheme has no rule")
    void activatingWithAGapIsRefused() {
        fixture.scenario().build();
        var created = categoryService.create(aCategory("IT_GATE_LATER", false));

        assertThatThrownBy(() ->
                categoryService.update(created.getId(), aCategory("IT_GATE_LATER", true)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("STANDARD");
    }

    @Test
    @DisplayName("with a rule for every active scheme it activates")
    void aCompleteMatrixActivates() {
        fixture.scenario().build();
        var created = categoryService.create(aCategory("IT_GATE_COMPLETE", false));

        entityManager.createNativeQuery("""
                INSERT INTO payroll_adjustment_category_scheme_rules
                    (compensation_scheme_id, payroll_adjustment_category_id, is_allowed,
                     calculation_mode, is_active, valid_from, created_at)
                SELECT s.id, :categoryId, TRUE, 'MANUAL', TRUE, DATE '2020-01-01', now()
                FROM compensation_schemes s
                WHERE s.is_active AND s.archived_at IS NULL
                """).setParameter("categoryId", created.getId()).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        var activated = categoryService.update(created.getId(), aCategory("IT_GATE_COMPLETE", true));

        assertThat(activated.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("an already-active category can still be edited, gap or no gap")
    void anAlreadyActiveCategoryIsNotBlocked() {
        fixture.scenario().build();

        // Only the TRANSITION is checked. A category whose matrix predates this
        // rule must not become impossible to rename.
        var existing = categoryService.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .findFirst().orElseThrow();

        PayrollAdjustmentCategoryCreateRequest edit = aCategory(existing.getCode(), true);
        edit.setName("Preimenovana");

        assertThat(categoryService.update(existing.getId(), edit).getName())
                .isEqualTo("Preimenovana");
    }

    // ── schemes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a scheme cannot be inserted active with a gap — enforced in SQL, where schemes are made")
    void aSchemeCannotBeInsertedActiveWithAGap() {
        fixture.scenario().build();
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO compensation_schemes (code, name, is_active, created_at)
                    VALUES ('IT-GATE-SCHEME', 'Gate test', TRUE, now())
                    """).executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ne može da se aktivira");
    }

    @Test
    @DisplayName("inactive is fine; activating it later is what gets checked")
    void activatingASchemeWithAGapIsRefused() {
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                INSERT INTO compensation_schemes (code, name, is_active, created_at)
                VALUES ('IT-GATE-SCHEME-2', 'Gate test 2', FALSE, now())
                """).executeUpdate();
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "UPDATE compensation_schemes SET is_active = TRUE WHERE code = 'IT-GATE-SCHEME-2'")
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("Nedostaje");
    }

    @Test
    @DisplayName("with a rule for every active category it activates — a gate, not a wall")
    void aSchemeWithAFullMatrixActivates() {
        // A separate test rather than the tail of the one above: a failed statement
        // aborts the transaction, so nothing after the expected exception can run.
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                INSERT INTO compensation_schemes (code, name, is_active, created_at)
                VALUES ('IT-GATE-SCHEME-3', 'Gate test 3', FALSE, now())
                """).executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO payroll_adjustment_category_scheme_rules
                    (compensation_scheme_id, payroll_adjustment_category_id, is_allowed,
                     calculation_mode, is_active, valid_from, created_at)
                SELECT s.id, c.id, TRUE, 'MANUAL', TRUE, DATE '2020-01-01', now()
                FROM compensation_schemes s, payroll_adjustment_categories c
                WHERE s.code = 'IT-GATE-SCHEME-3' AND c.is_active AND c.archived_at IS NULL
                """).executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE compensation_schemes SET is_active = TRUE WHERE code = 'IT-GATE-SCHEME-3'")
                .executeUpdate();
        entityManager.flush();

        assertThat(entityManager.createNativeQuery(
                "SELECT is_active FROM compensation_schemes WHERE code = 'IT-GATE-SCHEME-3'")
                .getSingleResult())
                .isEqualTo(Boolean.TRUE);
    }
}
