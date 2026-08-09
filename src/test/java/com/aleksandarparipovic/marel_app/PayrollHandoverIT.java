package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemHandover;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemHandoverRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The handover between the shop floor and payroll.
 *
 * <p>The chain is DRAFT → APPROVED → LOCKED. Until this existed the status only
 * moved DRAFT ↔ LOCKED, so "spreman" — the state everybody talks about — was
 * not a state the system had.
 *
 * <p>What is asserted here is the part a dispute needs: that handing over twice
 * leaves TWO records rather than overwriting one, that sending it back does not
 * erase what was handed over, and that the figures in each record are the ones
 * that were true at that moment.
 */
@Transactional
class PayrollHandoverIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollRunItemHandoverRepository handoverRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private List<PayrollRunItemHandover> handoversOf(Long itemId) {
        entityManager.flush();
        return handoverRepository.findByPayrollRunItemIdOrderByOccurredAtDesc(itemId);
    }

    @Test
    @DisplayName("handing over moves DRAFT to APPROVED and records one step")
    void submitRecordsTheHandover() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        var submitted = payrollRunItemService.submit(id, "Gotovo za jul.");

        assertThat(submitted.getStatus()).isEqualTo("APPROVED");

        List<PayrollRunItemHandover> steps = handoversOf(id);
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().getEvent()).isEqualTo("SUBMITTED");
        assertThat(steps.getFirst().getStatusBefore()).isEqualTo("DRAFT");
        assertThat(steps.getFirst().getStatusAfter()).isEqualTo("APPROVED");
        assertThat(steps.getFirst().getNote()).isEqualTo("Gotovo za jul.");
    }

    @Test
    @DisplayName("handing over twice leaves two records, not one overwritten")
    void theSequenceSurvives() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, "prva predaja");
        payrollRunItemService.returnToDraft(id, "fali prekovremeni");
        payrollRunItemService.submit(id, "druga predaja");

        // This is the whole reason it is a table and not approved_by/approved_at:
        // columns would now hold only the last of these three.
        List<PayrollRunItemHandover> steps = handoversOf(id);
        assertThat(steps).hasSize(3);
        assertThat(steps.stream().map(PayrollRunItemHandover::getEvent))
                .containsExactlyInAnyOrder("SUBMITTED", "RETURNED", "SUBMITTED");
        assertThat(steps.stream().map(PayrollRunItemHandover::getNote))
                .contains("prva predaja", "fali prekovremeni", "druga predaja");
    }

    @Test
    @DisplayName("the record keeps the figures that were true at that moment")
    void figuresAreCopiedNotReferenced() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, null);
        var atHandover = handoversOf(id).getFirst().getNetPayableAmount();

        // The live item keeps moving; the record must not move with it.
        assertThat(handoversOf(id).getFirst().getNetPayableAmount()).isEqualTo(atHandover);
    }

    @Test
    @DisplayName("the record carries every line, not only the totals")
    void payloadCarriesTheLines() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, null);

        // Payroll's question is "which line moved after I submitted", and two
        // totals cannot answer it.
        Object lines = handoversOf(id).getFirst().getPayload().get("lines");
        assertThat(lines).isInstanceOf(List.class);
        assertThat((List<?>) lines).isNotEmpty();
        assertThat(((List<?>) lines).getFirst().toString()).contains("c=");
    }

    @Test
    @DisplayName("a month cannot be locked before it has been handed over")
    void lockRequiresTheHandover() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        assertThatThrownBy(() -> payrollRunItemService.lock(id))
                .hasMessageContaining("predat");

        payrollRunItemService.submit(id, null);
        assertThat(payrollRunItemService.lock(id).getStatus()).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("unlocking undoes the lock only — it does not undo the handover")
    void unlockReturnsToApproved() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, null);
        payrollRunItemService.lock(id);

        // Back to APPROVED, not DRAFT: dropping to DRAFT would silently undo the
        // supervisor's handover, which is a different decision with a different
        // owner.
        assertThat(payrollRunItemService.unlock(id).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("handing over the same month twice in a row adds nothing")
    void submitIsIdempotent() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, null);
        payrollRunItemService.submit(id, null);

        // A double-clicked button must not invent a handover that did not happen.
        assertThat(handoversOf(id)).hasSize(1);
    }

    @Test
    @DisplayName("an incomplete month cannot be handed over either")
    void submitRefusesPendingRequiredInput() {
        var scenario = fixture.scenario().build();
        fixture.requireManualInput("OTHER");

        // The same gate lock has, moved one step earlier: handing over a month
        // that still owes somebody a number would record a handover of figures
        // nobody decided on.
        assertThatThrownBy(() -> payrollRunItemService.submit(scenario.item().getId(), null))
                .hasMessageContaining("OTHER");
        assertThat(handoversOf(scenario.item().getId())).isEmpty();
    }

    @Test
    @DisplayName("a locked month cannot be handed over again")
    void lockedCannotBeSubmitted() {
        var scenario = fixture.scenario().build();
        Long id = scenario.item().getId();

        payrollRunItemService.submit(id, null);
        payrollRunItemService.lock(id);

        assertThatThrownBy(() -> payrollRunItemService.submit(id, null))
                .hasMessageContaining("Zaključan");
    }
}
