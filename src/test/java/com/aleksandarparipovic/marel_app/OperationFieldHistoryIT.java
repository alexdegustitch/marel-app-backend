package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationFieldChangeDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operation page's "Istorija izmena": category and units-per-product
 * changes are recorded by the operations_history trigger and read back with
 * labels, newest first.
 *
 * <p>The writes go through the two single-field service methods the page
 * calls; the reads go through {@link OperationDetailService#getFieldHistory}.
 * Explicit flushes stand between them, because the history is written by a
 * database trigger and only an UPDATE that reached the database fires it.
 */
@Transactional
class OperationFieldHistoryIT extends AbstractIntegrationTest {

    @Autowired private OperationService operationService;
    @Autowired private OperationDetailService operationDetailService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private WorkCodeCategoryRepository workCodeCategoryRepository;
    @PersistenceContext private EntityManager em;

    private WorkCodeCategory anotherCategory(String no, String name) {
        return workCodeCategoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo(no)
                .categoryName(name)
                .type("WORK")
                .isPaid(true)
                .normMultiplier(1.0)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(true)
                .allowsParallelWork(false)
                .displayOrder(11)
                .baseCategory(true)
                .build());
    }

    @Test
    @DisplayName("category and units changes land in the history with labels, newest first")
    void changesAreRecordedAndReadBack() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        WorkCodeCategory first = scenario.workCategory();
        Operation operation = fixture.operation(first, 100); // unitsPerProduct = 1

        WorkCodeCategory second = anotherCategory("IT-FH-WC", "Ručna dorada");

        operationService.changeWorkCodeCategory(operation.getId(), second.getId());
        em.flush();

        operationService.changeUnitsPerProduct(operation.getId(), 4);
        em.flush();

        List<OperationFieldChangeDto> changes =
                operationDetailService.getFieldHistory(operation.getId());

        assertThat(changes).hasSize(2);

        OperationFieldChangeDto units = changes.get(0);
        assertThat(units.field()).isEqualTo(OperationFieldChangeDto.FIELD_UNITS);
        assertThat(units.oldValue()).isEqualTo("1");
        assertThat(units.newValue()).isEqualTo("4");

        OperationFieldChangeDto category = changes.get(1);
        assertThat(category.field()).isEqualTo(OperationFieldChangeDto.FIELD_CATEGORY);
        assertThat(category.oldValue()).contains(first.getCategoryNo());
        assertThat(category.newValue()).contains("IT-FH-WC").contains("Ručna dorada");
    }

    @Test
    @DisplayName("clearing the category is a recorded change to an empty value")
    void clearingCategoryIsRecorded() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation operation = fixture.operation(scenario.workCategory(), 100);

        operationService.changeWorkCodeCategory(operation.getId(), null);
        em.flush();

        List<OperationFieldChangeDto> changes =
                operationDetailService.getFieldHistory(operation.getId());
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().field()).isEqualTo(OperationFieldChangeDto.FIELD_CATEGORY);
        assertThat(changes.getFirst().oldValue()).isNotNull();
        assertThat(changes.getFirst().newValue()).isNull();
    }

    @Test
    @DisplayName("units edits keep the norm version in force in step with the columns")
    void unitsEditUpdatesNormHistory() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation operation = fixture.operation(scenario.workCategory(), 100);

        operationService.changeUnitsPerProduct(operation.getId(), 7);
        em.flush();

        var versions = operationDetailService.getNormHistory(operation.getId(), false);
        assertThat(versions).isNotEmpty();
        assertThat(versions.stream().filter(OperationNormVersionDto::current))
                .anySatisfy(v -> assertThat(v.unitsPerProduct()).isEqualTo(7));
    }

    @Test
    @DisplayName("a standalone operation refuses losing its category and refuses units")
    void standaloneGuards() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();

        OperationCreateRequest request = new OperationCreateRequest();
        request.setStandalone(true);
        request.setOperationName("IT-FH-Standalone");
        request.setWorkCodeCategoryId(scenario.workCategory().getId());
        OperationWithProductInfoRow standalone = operationService.create(request);

        assertThatThrownBy(() ->
                operationService.changeWorkCodeCategory(standalone.getOperationId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kategoriju");

        assertThatThrownBy(() ->
                operationService.changeUnitsPerProduct(standalone.getOperationId(), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normu");
    }

    @Test
    @DisplayName("units below 1 are refused")
    void unitsBelowOneRefused() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation operation = fixture.operation(scenario.workCategory(), 100);

        assertThatThrownBy(() -> operationService.changeUnitsPerProduct(operation.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
