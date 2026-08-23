package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationUpdateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormActivationDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which norm an operation works to is a decision, not the newest row.
 *
 * <p>The rules under test are the owner's, and each one is a rule the previous
 * "newest non-archived version wins" model could not express: archived norms
 * stay readable, an earlier norm can be put back in force, succession follows
 * the date the norm applies FROM, and a norm may be temporary on purpose.
 */
@Transactional
class OperationNormInForceIT extends AbstractIntegrationTest {

    @Autowired private OperationDetailService detail;
    @Autowired private OperationService operations;
    @Autowired private OperationRepository operationRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private static OperationNormVersionCreateRequest norm(int value, LocalDate from) {
        return new OperationNormVersionCreateRequest(value, 1, from, false, null);
    }

    private static OperationNormVersionCreateRequest temporaryNorm(int value) {
        return new OperationNormVersionCreateRequest(value, 1, null, true, "privremeno");
    }

    /** The operation the fixture builds already carries a seeded norm of its own. */
    private Operation normedOperation() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        return fixture.operation(scenario.workCategory(), 40);
    }

    @Test
    @DisplayName("a new norm takes force, and the operation's own columns follow it")
    void addingPutsInForce() {
        Operation operation = normedOperation();

        OperationNormVersionDto added = detail.addNorm(operation.getId(), norm(60, LocalDate.of(2026, 3, 1)), null);

        assertThat(added.current()).isTrue();
        assertThat(added.activatedAt()).isNotNull();

        Operation reread = operationRepository.findById(operation.getId()).orElseThrow();
        assertThat(reread.getMinNorm()).isEqualTo(60);
        assertThat(reread.getNormDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("archiving the norm in force hands over to the most recent by norm date")
    void archivingHandsOverByNormDate() {
        Operation operation = normedOperation();

        OperationNormVersionDto older = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        OperationNormVersionDto newer = detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);
        // Entered last, but it applies from the EARLIEST date of the three.
        OperationNormVersionDto backdated = detail.addNorm(operation.getId(), norm(30, LocalDate.of(2025, 5, 1)), null);
        assertThat(backdated.current()).isTrue();

        detail.archiveNorm(operation.getId(), backdated.id(), null);

        List<OperationNormVersionDto> history = detail.getNormHistory(operation.getId(), false);
        // June beats January; the order they were entered in does not decide it.
        assertThat(inForce(history).id()).isEqualTo(newer.id());
        assertThat(history).noneMatch(v -> v.id().equals(older.id()) && v.current());
        assertThat(operationRepository.findById(operation.getId()).orElseThrow().getMinNorm()).isEqualTo(70);
    }

    @Test
    @DisplayName("a temporary norm has no date and is ranked by when it was entered")
    void temporaryNormsRankByEntry() {
        Operation operation = normedOperation();

        detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);
        OperationNormVersionDto temporary = detail.addNorm(operation.getId(), temporaryNorm(85), null);

        assertThat(temporary.temporary()).isTrue();
        assertThat(temporary.normDate()).isNull();
        assertThat(operationRepository.findById(operation.getId()).orElseThrow().getNormDate()).isNull();

        // Entered after the June norm, so it inherits when the norm in force goes.
        OperationNormVersionDto latest = detail.addNorm(operation.getId(), norm(90, LocalDate.of(2026, 7, 1)), null);
        detail.archiveNorm(operation.getId(), latest.id(), null);

        assertThat(inForce(detail.getNormHistory(operation.getId(), false)).id()).isEqualTo(temporary.id());
    }

    @Test
    @DisplayName("a temporary norm cannot carry a date")
    void temporaryNormRejectsDate() {
        Operation operation = normedOperation();

        assertThatThrownBy(() -> detail.addNorm(
                operation.getId(),
                new OperationNormVersionCreateRequest(50, 1, LocalDate.of(2026, 2, 1), true, null),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Privremena");
    }

    @Test
    @DisplayName("any norm from the history can be put back in force, archived ones included")
    void activatingAnEarlierNorm() {
        Operation operation = normedOperation();

        OperationNormVersionDto first = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        OperationNormVersionDto second = detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);
        assertThat(second.current()).isTrue();

        // Archiving the one in force leaves the first one in force again...
        detail.archiveNorm(operation.getId(), second.id(), null);
        assertThat(inForce(detail.getNormHistory(operation.getId(), false)).id()).isEqualTo(first.id());

        // ...and the archived one is still readable, but only when asked for.
        assertThat(detail.getNormHistory(operation.getId(), false)).noneMatch(v -> v.id().equals(second.id()));
        OperationNormVersionDto archived = detail.getNormHistory(operation.getId(), true).stream()
                .filter(v -> v.id().equals(second.id())).findFirst().orElseThrow();
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.current()).isFalse();

        // Putting it back in force un-archives it, in the same step.
        OperationNormVersionDto restored = detail.activateNorm(operation.getId(), second.id(), "vraćena stara norma", null);
        assertThat(restored.current()).isTrue();
        assertThat(restored.archivedAt()).isNull();
        assertThat(inForce(detail.getNormHistory(operation.getId(), false)).id()).isEqualTo(second.id());
        assertThat(operationRepository.findById(operation.getId()).orElseThrow().getMinNorm()).isEqualTo(70);
    }

    @Test
    @DisplayName("every decision leaves an entry, and an entry ends where the next begins")
    void chronologyIsRecorded() {
        Operation operation = normedOperation();

        OperationNormVersionDto first = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);
        detail.activateNorm(operation.getId(), first.id(), "greška u merenju", null);

        List<OperationNormActivationDto> chronology = detail.getNormActivations(operation.getId());

        assertThat(chronology).hasSize(3);
        // Newest first: the decision to go back to the first norm.
        assertThat(chronology.getFirst().normVersionId()).isEqualTo(first.id());
        assertThat(chronology.getFirst().source()).isEqualTo("ACTIVATED");
        assertThat(chronology.getFirst().reason()).isEqualTo("greška u merenju");
        // Still in force, and its norm is not archived, so the entry has no end.
        assertThat(chronology.getFirst().until()).isNull();
        // The one before it ended exactly when this decision was made.
        assertThat(chronology.get(1).until()).isEqualTo(chronology.getFirst().activatedAt());
        assertThat(chronology.get(2).source()).isEqualTo("ADDED");
    }

    @Test
    @DisplayName("only the norm in force may be edited or archived")
    void olderVersionsAreHistory() {
        Operation operation = normedOperation();

        OperationNormVersionDto first = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);

        assertThatThrownBy(() -> detail.updateNorm(operation.getId(), first.id(), norm(55, LocalDate.of(2026, 1, 1)), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> detail.archiveNorm(operation.getId(), first.id(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("archiving the last norm leaves the operation un-normed rather than guessing")
    void lastNormLeavesNothingBehind() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation operation = fixture.operation(scenario.workCategory(), 40);

        OperationNormVersionDto only = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        detail.archiveNorm(operation.getId(), only.id(), null);

        Operation reread = operationRepository.findById(operation.getId()).orElseThrow();
        assertThat(reread.getMinNorm()).isNull();
        assertThat(reread.isNormRequired()).isFalse();
        assertThat(reread.getNormDate()).isNull();
        assertThat(detail.getNormHistory(operation.getId(), false)).isEmpty();
    }


    // ── The operation form writes the same fact from the other direction ────

    /** The form as the operations list sends it. */
    private static OperationUpdateRequest formUpdate(String name, Integer norm, LocalDate normDate) {
        OperationUpdateRequest request = new OperationUpdateRequest();
        request.setOperationName(name);
        request.setMinNorm(norm);
        request.setMaxNorm(norm);
        request.setNormRequired(norm != null);
        request.setUnitsPerProduct(1);
        request.setNormDate(normDate);
        return request;
    }

    @Test
    @DisplayName("an operation created with a norm starts its history with that norm")
    void creatingWithANormRecordsIt() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation existing = fixture.operation(scenario.workCategory(), 40);

        OperationCreateRequest request = new OperationCreateRequest();
        request.setProductId(existing.getProduct().getId());
        request.setOperationName("IT-OP-forma");
        request.setMinNorm(64);
        request.setMaxNorm(64);
        request.setNormRequired(true);
        request.setUnitsPerProduct(2);
        request.setNormDate(LocalDate.of(2026, 4, 1));

        Long createdId = operations.create(request).getOperationId();

        List<OperationNormVersionDto> history = detail.getNormHistory(createdId, false);
        assertThat(history).hasSize(1);
        assertThat(inForce(history).minNorm()).isEqualTo(64);
        assertThat(detail.getNormActivations(createdId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.source()).isEqualTo("ADDED"));
    }

    @Test
    @DisplayName("changing the norm through the form edits the one in force rather than adding a version")
    void formEditsTheNormInForce() {
        Operation operation = normedOperation();
        OperationNormVersionDto original = detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);

        operations.updateOperation(operation.getId(), formUpdate(operation.getOpName(), 75, LocalDate.of(2026, 2, 1)));

        List<OperationNormVersionDto> history = detail.getNormHistory(operation.getId(), false);
        // Same version, new values — the owner's rule, and what "Izmeni važeću
        // normu" on the operation page does.
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().id()).isEqualTo(original.id());
        assertThat(history.getFirst().minNorm()).isEqualTo(75);
        assertThat(history.getFirst().normDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(history.getFirst().current()).isTrue();

        assertThat(detail.getNormActivations(operation.getId()).getFirst().source()).isEqualTo("EDITED");
    }

    @Test
    @DisplayName("saving the form without touching the norm leaves the chronology alone")
    void formWithoutANormChangeRecordsNothing() {
        Operation operation = normedOperation();
        detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        int before = detail.getNormActivations(operation.getId()).size();

        operations.updateOperation(operation.getId(), formUpdate("IT-OP-preimenovana", 50, LocalDate.of(2026, 1, 1)));

        assertThat(detail.getNormActivations(operation.getId())).hasSize(before);
    }

    @Test
    @DisplayName("clearing the norm through the form ends it instead of promoting an older one")
    void formClearingTheNormEndsIt() {
        Operation operation = normedOperation();
        detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);
        detail.addNorm(operation.getId(), norm(70, LocalDate.of(2026, 6, 1)), null);

        OperationUpdateRequest cleared = formUpdate(operation.getOpName(), null, null);
        cleared.setUnitsPerProduct(null);
        operations.updateOperation(operation.getId(), cleared);

        // The form said this operation has no norm; inheriting one would overrule it.
        assertThat(detail.getNormHistory(operation.getId(), false))
                .noneMatch(OperationNormVersionDto::current);
        assertThat(operationRepository.findById(operation.getId()).orElseThrow().getMinNorm()).isNull();
    }


    @Test
    @DisplayName("a norm is optional, and without one no date is kept")
    void normIsOptionalAndTakesNoDate() {
        Operation operation = normedOperation();

        // A date was offered; there is no norm for it to date, so it is dropped
        // rather than stored against nothing.
        OperationNormVersionDto undated = detail.addNorm(
                operation.getId(),
                new OperationNormVersionCreateRequest(null, 2, LocalDate.of(2026, 3, 1), false, "još nije izmereno"),
                null);

        assertThat(undated.minNorm()).isNull();
        assertThat(undated.normDate()).isNull();
        assertThat(undated.temporary()).isFalse();
        assertThat(undated.current()).isTrue();

        Operation reread = operationRepository.findById(operation.getId()).orElseThrow();
        assertThat(reread.getMinNorm()).isNull();
        assertThat(reread.getNormDate()).isNull();
        assertThat(reread.isNormRequired()).isFalse();
    }

    @Test
    @DisplayName("the operation form keeps no norm date once the norm itself is gone")
    void formDropsTheDateWithTheNorm() {
        Operation operation = normedOperation();
        detail.addNorm(operation.getId(), norm(50, LocalDate.of(2026, 1, 1)), null);

        OperationUpdateRequest withoutNorm = formUpdate(operation.getOpName(), null, LocalDate.of(2026, 9, 9));
        operations.updateOperation(operation.getId(), withoutNorm);

        assertThat(operationRepository.findById(operation.getId()).orElseThrow().getNormDate()).isNull();
    }

    private static OperationNormVersionDto inForce(List<OperationNormVersionDto> history) {
        return history.stream().filter(OperationNormVersionDto::current).findFirst().orElseThrow();
    }
}
