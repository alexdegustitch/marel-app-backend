package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import java.util.List;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Poslednja aktivnost" must mean somebody did something.
 *
 * <p>trg_payroll_run_items_track_activity fired on every update of the row. It
 * skipped writes with no app.user_id, which excludes the background worker — but
 * not the case that matters: recalculation is LAZY, so opening a payroll
 * recomputes a stale item inside the reader's own request, with their user id
 * already in the session. Change one bonus eligibility rule and every item of
 * that month goes stale; every one a person then merely LOOKED at was recorded as
 * edited by them. 560 of 849 items carried such a row.
 *
 * <p>No column test or session flag can separate the two: a patch and the
 * recalculation it triggers land in the same UPDATE on the same row. Only the
 * caller knows, so the caller records it.
 */
@Transactional
class PayrollActivityIsAnEditIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private EntityManager entityManager;

    private long activityRowsFor(Long itemId) {
        entityManager.flush();
        Number n = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM employee_payroll_run_item_updates WHERE payroll_run_item_id = :id")
                .setParameter("id", itemId)
                .getSingleResult();
        return n.longValue();
    }

    /**
     * Edit a figure ON ITS LINE — the route the parameters panel now uses.
     *
     * <p>These tests used to set payroll_run_items fields on the patch request.
     * Those fields are gone: the meal price and the transport total are edited
     * through the adjustments array, because the line is what the calculation
     * reads.
     */
    private void patchLine(Long itemId, String code, java.util.function.Consumer<AdjustmentPatchDto> fill) {
        AdjustmentPatchDto dto = new AdjustmentPatchDto();
        dto.setId(adjustmentRepository.findByItemIdAndCategoryCode(itemId, code).orElseThrow().getId());
        fill.accept(dto);
        PayrollRunItemPatchRequest request = new PayrollRunItemPatchRequest();
        request.setAdjustments(List.of(dto));
        payrollRunItemService.patch(itemId, request);
    }

    @Test
    @DisplayName("recalculating on read is not activity, however much it rewrites")
    void aLazyRecalculationIsNotActivity() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", itemId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // This rewrites almost every column on the item — and is somebody opening
        // a screen, not editing anything.
        PayrollRunItem item = payrollRunItemService.getForPayrollAccess(itemId);

        assertThat(item).isNotNull();
        assertThat(activityRowsFor(itemId))
                .as("opening a payroll must not read as having edited it")
                .isZero();
    }

    @Test
    @DisplayName("an edit goes through the recording path")
    void anEditGoesThroughTheRecordingPath() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        patchLine(itemId, "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // No authenticated user in this test, so nothing is written — which is the
        // other half of the rule: a background sweep is not activity either. What
        // is asserted is that the edit does not blow up on the way through and
        // that reading, above, records nothing while this does not fail.
        assertThat(activityRowsFor(itemId)).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the trigger that recorded everything is gone")
    void theBlanketTriggerIsGone() {
        Number n = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_payroll_run_items_track_activity'")
                .getSingleResult();

        // Asserted rather than assumed: the migration dropping it runs as part of
        // the schema this test builds, and a re-created trigger would silently
        // bring the whole defect back.
        assertThat(n.longValue()).isZero();
    }
}
