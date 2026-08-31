package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.monthly_scrap.MonthlyScrapRepository;
import com.aleksandarparipovic.marel_app.monthly_scrap.MonthlyScrapService;
import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapResponse;
import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapSaveRequest;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Counting the scrap nobody reported.
 *
 * <p>The schema rules are asserted in {@link MonthlyScrapSchemaIT}; what is
 * asserted here is the behaviour the service adds on top of them — that the
 * month comes from the screen, that a removed row is kept, and that a mismatched
 * product is refused with a sentence rather than a constraint violation.
 */
@Transactional
class MonthlyScrapServiceIT extends AbstractIntegrationTest {

    @Autowired private MonthlyScrapService monthlyScrapService;
    @Autowired private MonthlyScrapRepository monthlyScrapRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("a counted row comes back with the names the list shows")
    void createReturnsTheRowResolved() {
        Operation operation = anOperation();

        MonthlyScrapResponse created = monthlyScrapService.create(2026, 8, request(operation, 4, "Popis"));

        assertThat(created.getPeriod()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(created.getQuantity()).isEqualTo(4);
        assertThat(created.getNote()).isEqualTo("Popis");
        assertThat(created.getProductName()).isEqualTo(operation.getProduct().getProductName());
        assertThat(created.getOperationName()).isEqualTo(operation.getOpName());
        assertThat(created.getProductionOrderId()).isNull();
    }

    @Test
    @DisplayName("the period is the first of the month the screen is showing")
    void thePeriodIsAlwaysTheFirstOfTheMonth() {
        // Never taken from a body: chk_monthly_scraps_period_month would refuse
        // anything else, and a period a client could choose is a count filed
        // under a month nobody is looking at.
        Operation operation = anOperation();

        monthlyScrapService.create(2026, 12, request(operation, 1, null));
        entityManager.flush();

        assertThat(monthlyScrapService.findForMonth(2026, 12)).hasSize(1);
        assertThat(monthlyScrapService.findForMonth(2026, 11)).isEmpty();
    }

    @Test
    @DisplayName("a month outside 1–12 is refused before it reaches the database")
    void anImpossibleMonthIsRefused() {
        Operation operation = anOperation();

        assertThatThrownBy(() -> monthlyScrapService.create(2026, 13, request(operation, 1, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mesec");
    }

    @Test
    @DisplayName("an operation that is not the product's own is refused in words")
    void aMismatchedProductIsRefused() {
        Operation operation = anOperation();
        Product other = fixture.product("Neki drugi proizvod");

        MonthlyScrapSaveRequest request = request(operation, 3, null);
        request.setProductId(other.getId());

        // The composite foreign key says the same thing, but says it as a
        // constraint violation. This is the version somebody can act on.
        assertThatThrownBy(() -> monthlyScrapService.create(2026, 8, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne pripada");
    }

    @Test
    @DisplayName("a blank note is stored as no note")
    void aBlankNoteIsNoNote() {
        Operation operation = anOperation();

        MonthlyScrapResponse created = monthlyScrapService.create(2026, 8, request(operation, 2, "   "));

        assertThat(created.getNote()).isNull();
    }

    @Test
    @DisplayName("an update replaces the whole row, including clearing the order")
    void updateReplacesEveryField() {
        Operation first = anOperation();
        Operation second = anOperation();

        MonthlyScrapResponse created = monthlyScrapService.create(2026, 8, request(first, 4, "prvo"));

        MonthlyScrapSaveRequest replacement = request(second, 9, null);
        MonthlyScrapResponse updated = monthlyScrapService.update(created.getId(), replacement);

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getOperationId()).isEqualTo(second.getId());
        assertThat(updated.getProductId()).isEqualTo(second.getProduct().getId());
        assertThat(updated.getQuantity()).isEqualTo(9);
        // The whole-row PUT is what makes this possible at all: a patch cannot
        // tell "leave the note" from "remove it".
        assertThat(updated.getNote()).isNull();
    }

    @Test
    @DisplayName("removing a row hides it from the month but keeps it")
    void deleteDeactivatesRatherThanErases() {
        Operation operation = anOperation();
        MonthlyScrapResponse created = monthlyScrapService.create(2026, 8, request(operation, 6, null));
        entityManager.flush();

        monthlyScrapService.delete(created.getId());
        entityManager.flush();

        assertThat(monthlyScrapService.findForMonth(2026, 8)).isEmpty();

        var stored = monthlyScrapRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getIsActive()).isFalse();
        // set_archived_at_on_deactivate stamps when; the audit trigger, who.
        assertThat(stored.getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("removing the same row twice is not an error")
    void deleteIsIdempotent() {
        Operation operation = anOperation();
        MonthlyScrapResponse created = monthlyScrapService.create(2026, 8, request(operation, 1, null));

        monthlyScrapService.delete(created.getId());
        monthlyScrapService.delete(created.getId());

        assertThat(monthlyScrapService.findForMonth(2026, 8)).isEmpty();
    }

    @Test
    @DisplayName("the month reads in product then operation order")
    void theListIsOrderedForReading() {
        var category = fixture.scenario().build().workCategory();
        Product beta = fixture.product("Beta");
        Product alfa = fixture.product("Alfa");

        Operation onBeta = fixture.operation(beta, category, 10);
        Operation onAlfa = fixture.operation(alfa, category, 10);

        monthlyScrapService.create(2026, 8, request(onBeta, 1, null));
        monthlyScrapService.create(2026, 8, request(onAlfa, 1, null));
        entityManager.flush();

        List<MonthlyScrapResponse> rows = monthlyScrapService.findForMonth(2026, 8);
        assertThat(rows).extracting(MonthlyScrapResponse::getProductName)
                .containsExactly("Alfa", "Beta");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Operation anOperation() {
        var category = fixture.scenario().build().workCategory();
        Operation operation = fixture.operation(category, 10);
        entityManager.flush();
        return operation;
    }

    private MonthlyScrapSaveRequest request(Operation operation, int quantity, String note) {
        MonthlyScrapSaveRequest request = new MonthlyScrapSaveRequest();
        request.setOperationId(operation.getId());
        request.setProductId(operation.getProduct().getId());
        request.setQuantity(quantity);
        request.setNote(note);
        return request;
    }
}
