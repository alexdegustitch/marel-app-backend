package com.aleksandarparipovic.marel_app.payroll_run_item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose history the payroll's timeline is.
 *
 * <p>Payroll sees the whole chain. A supervisor sees their own half of it — the
 * handover, and the requests they raised — and not what payroll does with the
 * month afterwards.
 *
 * <p>Pinned as a test because the set is a WHITELIST, and the failure mode of a
 * whitelist is somebody adding an entry to it while adding an event. That is one
 * line in a diff and it silently shows a supervisor payroll's working record.
 */
class HandoverVisibilityTest {

    @Test
    @DisplayName("the supervisor sees the handover chain and the requests they raised")
    void showsTheirOwnWorkflow() {
        assertThat(PayrollRunItemHandover.EVENTS_VISIBLE_TO_SUBMITTER)
                .contains(
                        PayrollRunItemHandover.EVENT_CREATED,
                        PayrollRunItemHandover.EVENT_SUBMITTED,
                        PayrollRunItemHandover.EVENT_RETURNED,
                        PayrollRunItemHandover.EVENT_CHANGE_REQUESTED,
                        PayrollRunItemHandover.EVENT_CHANGE_ACCEPTED,
                        PayrollRunItemHandover.EVENT_CHANGE_DECLINED);
    }

    @Test
    @DisplayName("and never what payroll does with the month afterwards")
    void hidesPayrollsOwnSteps() {
        // Freezing the month and reopening it are payroll's record. A supervisor
        // reading "završen / otključan / završen" is reading somebody else's
        // working notes about a decision that was never theirs.
        assertThat(PayrollRunItemHandover.EVENTS_VISIBLE_TO_SUBMITTER)
                .doesNotContain(
                        PayrollRunItemHandover.EVENT_LOCKED,
                        PayrollRunItemHandover.EVENT_UNLOCKED);
    }

    @Test
    @DisplayName("the whitelist is exactly six events, so a seventh has to be a decision")
    void isClosed() {
        // Not a count for its own sake: adding an event and adding it here are
        // two different acts, and this makes the second one deliberate.
        assertThat(PayrollRunItemHandover.EVENTS_VISIBLE_TO_SUBMITTER).hasSize(6);
    }
}
