package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.CopyOperationsRequest;
import com.aleksandarparipovic.marel_app.operation.dto.CopyOperationsResult;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making a NEW operation from an existing one brings the norm along.
 *
 * <p>The rule the owner settled: the copy is a SNAPSHOT of the source as it
 * stands today — the norm, the date that norm applies from, or "privremena"
 * where the source deliberately carries no date, plus the units and the
 * description. What the copy never takes is the source's norm HISTORY: it opens
 * its own, with one entry recorded the moment it is created, so neither
 * operation's audit trail can be read as the other's.
 *
 * <p>Two ways in are pinned here because the application uses both: the
 * add-operations drawer on the product page, which creates each chosen operation
 * through the operation form carrying the source's norm, and the
 * {@code copy-to-product} endpoint, which copies server-side.
 */
@Transactional
class OperationCopyCarriesTheNormIT extends AbstractIntegrationTest {

    @Autowired private OperationService operations;
    @Autowired private OperationDetailService detail;
    @Autowired private OperationRepository operationRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private static final LocalDate NORM_FROM = LocalDate.of(2026, 3, 1);

    /** A source operation with a dated norm of its own and a description. */
    private Operation sourceWithDatedNorm() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation source = fixture.operation(scenario.workCategory(), 40);
        source.setDescription("Šije se po šablonu A");
        operationRepository.saveAndFlush(source);
        detail.addNorm(source.getId(),
                new OperationNormVersionCreateRequest(120, 2, NORM_FROM, false, null), null);
        return operationRepository.findById(source.getId()).orElseThrow();
    }

    private static OperationNormVersionDto inForce(List<OperationNormVersionDto> history) {
        return history.stream().filter(OperationNormVersionDto::current).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the drawer's copy carries the source's norm, its date and its description, and opens its own history")
    void formCopyCarriesTheNorm() {
        Operation source = sourceWithDatedNorm();
        Product target = fixture.product("IT-Proizvod-" + System.nanoTime());

        // Exactly what the add-operations drawer now sends for a row seeded from
        // an existing operation: the source's norm, dated as the source dates it.
        OperationCreateRequest request = new OperationCreateRequest();
        request.setProductId(target.getId());
        request.setOperationName("Šivenje gornjeg dela");
        request.setDescription(source.getDescription());
        request.setMinNorm(source.getMinNorm());
        request.setMaxNorm(source.getMaxNorm());
        request.setNormRequired(true);
        request.setUnitsPerProduct(source.getUnitsPerProduct());
        request.setNormDate(source.getNormDate());
        request.setTemporary(source.isTemporary());

        Long copyId = operations.create(request).getOperationId();

        Operation copy = operationRepository.findById(copyId).orElseThrow();
        assertThat(copy.getMinNorm()).isEqualTo(120);
        assertThat(copy.getMaxNorm()).isEqualTo(120);
        assertThat(copy.getUnitsPerProduct()).isEqualTo(2);
        assertThat(copy.getNormDate()).isEqualTo(NORM_FROM);
        assertThat(copy.isTemporary()).isFalse();
        assertThat(copy.getDescription()).isEqualTo("Šije se po šablonu A");

        // Its own history, starting at the copied norm.
        List<OperationNormVersionDto> history = detail.getNormHistory(copyId, false);
        assertThat(history).hasSize(1);
        assertThat(inForce(history).minNorm()).isEqualTo(120);
        assertThat(inForce(history).normDate()).isEqualTo(NORM_FROM);
        assertThat(detail.getNormActivations(copyId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.source()).isEqualTo("ADDED"));

        // And the source's own history is untouched — one norm, still in force.
        List<OperationNormVersionDto> sourceHistory = detail.getNormHistory(source.getId(), false);
        assertThat(sourceHistory).hasSize(1);
        assertThat(inForce(sourceHistory).minNorm()).isEqualTo(120);
        assertThat(detail.getNormActivations(source.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a provisional (undated) norm stays provisional on the copy")
    void provisionalNormStaysProvisional() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Operation source = fixture.operation(scenario.workCategory(), 40);
        detail.addNorm(source.getId(),
                new OperationNormVersionCreateRequest(90, 1, null, true, "privremeno"), null);
        Operation reread = operationRepository.findById(source.getId()).orElseThrow();
        assertThat(reread.isTemporary()).isTrue();

        Product target = fixture.product("IT-Proizvod-" + System.nanoTime());
        OperationCreateRequest request = new OperationCreateRequest();
        request.setProductId(target.getId());
        request.setOperationName("Privremena kopija");
        request.setMinNorm(reread.getMinNorm());
        request.setMaxNorm(reread.getMaxNorm());
        request.setNormRequired(true);
        request.setUnitsPerProduct(reread.getUnitsPerProduct());
        request.setNormDate(reread.getNormDate());
        request.setTemporary(reread.isTemporary());

        Long copyId = operations.create(request).getOperationId();

        Operation copy = operationRepository.findById(copyId).orElseThrow();
        assertThat(copy.getMinNorm()).isEqualTo(90);
        // No date, by decision — and the history says the same thing.
        assertThat(copy.getNormDate()).isNull();
        assertThat(copy.isTemporary()).isTrue();
        assertThat(inForce(detail.getNormHistory(copyId, false)).temporary()).isTrue();
    }

    @Test
    @DisplayName("copy-to-product copies the whole norm snapshot, including the description")
    void serverSideCopyCarriesTheNorm() {
        Operation source = sourceWithDatedNorm();
        Product target = fixture.product("IT-Proizvod-" + System.nanoTime());

        CopyOperationsRequest request = new CopyOperationsRequest();
        request.setTargetProductId(target.getId());
        request.setOperationIds(List.of(source.getId()));

        CopyOperationsResult result = operations.copyOperationsToProduct(request);

        assertThat(result.copied()).containsExactly(source.getOpName());
        assertThat(result.skipped()).isEmpty();

        Operation copy = operationRepository.findByProductIdAndArchivedAtIsNull(target.getId())
                .stream().filter(o -> o.getOpName().equals(source.getOpName())).findFirst().orElseThrow();
        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(copy.getMinNorm()).isEqualTo(120);
        assertThat(copy.getUnitsPerProduct()).isEqualTo(2);
        assertThat(copy.getNormDate()).isEqualTo(NORM_FROM);
        assertThat(copy.isTemporary()).isFalse();
        assertThat(copy.getDescription()).isEqualTo("Šije se po šablonu A");

        List<OperationNormVersionDto> history = detail.getNormHistory(copy.getId(), false);
        assertThat(history).hasSize(1);
        assertThat(inForce(history).normDate()).isEqualTo(NORM_FROM);
        assertThat(detail.getNormHistory(source.getId(), false)).hasSize(1);
    }
}
