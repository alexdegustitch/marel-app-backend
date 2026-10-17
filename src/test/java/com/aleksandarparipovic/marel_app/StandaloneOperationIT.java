package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.analytics.AnalyticsFactSyncService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationBasicInfoDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Standalone (osnovne) operations: no product, no norm, category required.
 *
 * <p>Covers the three places the feature lives: the V65 conversion (the
 * technical NO/ND pairs V25 created are standalone after a fresh migration
 * run), the service rules for creating and reading them, and the derived
 * paths that used to assume every operation has a product (analytics facts,
 * the operation page's order reads, archive blockers).
 */
@Transactional
class StandaloneOperationIT extends AbstractIntegrationTest {

    @Autowired private OperationService operationService;
    @Autowired private OperationDetailService operationDetailService;
    @Autowired private OperationRepository operationRepository;
    @Autowired private AnalyticsFactSyncService analyticsFactSyncService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("V65 made the technical NO/ND operations standalone and archived their products")
    void v65ConvertsTechnicalAbsencePairs() {
        for (String code : List.of("NO", "ND")) {
            List<Operation> operations = operationRepository.findActiveByWorkCodeCategoryNo(code);
            assertThat(operations)
                    .as("exactly one live operation for category %s", code)
                    .hasSize(1);
            Operation operation = operations.getFirst();
            assertThat(operation.isStandalone()).as("%s is standalone", code).isTrue();
            assertThat(operation.getProduct()).as("%s has no product", code).isNull();
            assertThat(operation.isNormRequired()).isFalse();
            assertThat(operation.getWorkCodeCategory()).isNotNull();
        }

        // The technical products V25 created are archived — nothing referenced them.
        List<String> stillActive = jdbc.queryForList(
                "SELECT product_code FROM products WHERE product_code IN ('NO','ND') AND archived_at IS NULL",
                String.class);
        assertThat(stillActive).isEmpty();
    }

    @Test
    @DisplayName("a standalone operation is created without a product and refuses one, a norm, or a missing category")
    void createRules() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Long categoryId = scenario.workCategory().getId();

        OperationCreateRequest ok = new OperationCreateRequest();
        ok.setStandalone(true);
        ok.setOperationName("IT-Pauza");
        ok.setWorkCodeCategoryId(categoryId);
        OperationWithProductInfoRow row = operationService.create(ok);
        assertThat(row.isStandalone()).isTrue();
        assertThat(row.getProductId()).isNull();
        assertThat(row.getProductName()).isNull();
        assertThat(row.getWorkCodeCategoryId()).isEqualTo(categoryId);

        // Offered to the karton through the standalone options.
        List<OperationBasicInfoDto> options = operationService.getStandaloneOperations(LocalDate.now());
        assertThat(options).extracting(OperationBasicInfoDto::name).contains("IT-Pauza");

        // Nothing product-scoped exists for it: no orders, nothing blocking an archive.
        assertThat(operationDetailService.getProductionOrders(row.getOperationId())).isEmpty();
        assertThat(operationDetailService.getSampleOrders(row.getOperationId())).isEmpty();
        assertThat(operationDetailService.getArchiveBlockers(row.getOperationId())).isEmpty();

        OperationCreateRequest withProduct = new OperationCreateRequest();
        withProduct.setStandalone(true);
        withProduct.setOperationName("IT-Pauza-2");
        withProduct.setWorkCodeCategoryId(categoryId);
        withProduct.setProductId(fixture.product("IT-NotForStandalone").getId());
        assertThatThrownBy(() -> operationService.create(withProduct))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne pripada proizvodu");

        OperationCreateRequest withNorm = new OperationCreateRequest();
        withNorm.setStandalone(true);
        withNorm.setOperationName("IT-Pauza-3");
        withNorm.setWorkCodeCategoryId(categoryId);
        withNorm.setMinNorm(10);
        assertThatThrownBy(() -> operationService.create(withNorm))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nema normu");

        OperationCreateRequest withoutCategory = new OperationCreateRequest();
        withoutCategory.setStandalone(true);
        withoutCategory.setOperationName("IT-Pauza-4");
        assertThatThrownBy(() -> operationService.create(withoutCategory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kategoriju rada");
    }

    @Test
    @DisplayName("a karton with a standalone log still reaches the analytics facts — with no product")
    void analyticsFactsSurviveAStandaloneLog() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        WorkShift shift = fixture.workShift(scenario.employee(), LocalDate.now(), 6, 480);

        OperationCreateRequest request = new OperationCreateRequest();
        request.setStandalone(true);
        request.setOperationName("IT-Bolovanje");
        request.setWorkCodeCategoryId(scenario.workCategory().getId());
        Long operationId = operationService.create(request).getOperationId();
        Operation standalone = operationRepository.findById(operationId).orElseThrow();

        WorkLog log = fixture.workLog(shift, standalone, scenario.workCategory(), 0, 480, 0);

        analyticsFactSyncService.upsertFactsForShift(shift, List.of(log), false);

        List<Long> productIds = jdbc.queryForList(
                "SELECT product_id FROM work_log_facts WHERE work_log_id = " + log.getId(), Long.class);
        assertThat(productIds).containsExactly((Long) null);
    }
}
