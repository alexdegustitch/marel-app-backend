package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.CopyOperationsResult;
import com.aleksandarparipovic.marel_app.operation.dto.CopyTypeOperationsRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_family.ProductFamilyRepository;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.product_type_operation.ProductTypeOperation;
import com.aleksandarparipovic.marel_app.product_type_operation.ProductTypeOperationRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A product type carries a TEMPLATE of operations; a product may copy it, and once
 * copied the operations are independent.
 *
 * <p>The three rules under test are the ones the design commits to: copying brings
 * the definition and a SUGGESTED norm (undated, so provisional) and starts the
 * product operation's own norm history; a name the product already carries is
 * skipped, not refused; and editing the template afterwards does NOT reach back
 * into a product already created from it — the copy is a snapshot.
 */
@Transactional
class ProductTypeOperationTemplateIT extends AbstractIntegrationTest {

    @Autowired private OperationService operations;
    @Autowired private OperationDetailService detail;
    @Autowired private OperationRepository operationRepository;
    @Autowired private ProductTypeOperationRepository templateRepository;
    @Autowired private ProductTypeRepository typeRepository;
    @Autowired private ProductFamilyRepository familyRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private ProductType aType() {
        ProductFamily family = familyRepository.saveAndFlush(
                ProductFamily.builder().name("IT-Porodica-" + System.nanoTime()).build());
        return typeRepository.saveAndFlush(ProductType.builder()
                .family(family)
                .name("IT-Tip")
                .code("IT" + (System.nanoTime() % 100000))
                .build());
    }

    private ProductTypeOperation template(ProductType type, String opName,
                                          WorkCodeCategory category, Integer norm) {
        return templateRepository.saveAndFlush(ProductTypeOperation.builder()
                .productType(type)
                .opName(opName)
                .workCodeCategory(category)
                .defaultMinNorm(norm)
                .defaultMaxNorm(norm)
                .defaultUnitsPerProduct(norm == null ? null : 1)
                .normRequired(norm != null)
                .build());
    }

    @Test
    @DisplayName("copying a template onto a product brings a suggested, undated (provisional) norm and starts its history")
    void copyingBringsAProvisionalNorm() {
        WorkCodeCategory category = fixture.scenario().build().workCategory();
        ProductType type = aType();
        ProductTypeOperation blueprint = template(type, "Krimpovanje", category, 80);
        Product product = fixture.product("IT-Proizvod-" + System.nanoTime());

        CopyTypeOperationsRequest request = new CopyTypeOperationsRequest();
        request.setTargetProductId(product.getId());
        request.setProductTypeOperationIds(List.of(blueprint.getId()));

        CopyOperationsResult result = operations.copyTypeOperationsToProduct(request);

        assertThat(result.copied()).containsExactly("Krimpovanje");
        assertThat(result.skipped()).isEmpty();

        Operation copied = operationRepository.findByProductIdAndArchivedAtIsNull(product.getId())
                .stream().filter(o -> o.getOpName().equals("Krimpovanje")).findFirst().orElseThrow();
        assertThat(copied.getMinNorm()).isEqualTo(80);
        assertThat(copied.getWorkCodeCategory().getId()).isEqualTo(category.getId());
        // The template has no date, so the copied norm is undated — "privremena".
        assertThat(copied.getNormDate()).isNull();
        assertThat(copied.isTemporary()).isTrue();

        // The norm history starts where the template suggested.
        List<OperationNormVersionDto> history = detail.getNormHistory(copied.getId(), false);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().minNorm()).isEqualTo(80);
        assertThat(history.getFirst().current()).isTrue();
    }

    @Test
    @DisplayName("a name the product already carries is skipped, not refused")
    void duplicateNamesAreSkipped() {
        WorkCodeCategory category = fixture.scenario().build().workCategory();
        ProductType type = aType();
        ProductTypeOperation krimp = template(type, "Krimpovanje", category, 80);
        ProductTypeOperation rezanje = template(type, "Rezanje", category, 50);
        Product product = fixture.product("IT-Proizvod-" + System.nanoTime());
        // The product already has an operation named (case-insensitively) the same.
        fixture.operation(product, category, 40); // this one has its own generated name
        Operation existing = operationRepository.findByProductIdAndArchivedAtIsNull(product.getId()).getFirst();
        existing.setOpName("krimpovanje");
        operationRepository.saveAndFlush(existing);

        CopyTypeOperationsRequest request = new CopyTypeOperationsRequest();
        request.setTargetProductId(product.getId());
        request.setProductTypeOperationIds(List.of(krimp.getId(), rezanje.getId()));

        CopyOperationsResult result = operations.copyTypeOperationsToProduct(request);

        assertThat(result.copied()).containsExactly("Rezanje");
        assertThat(result.skipped()).containsExactly("Krimpovanje");
    }

    @Test
    @DisplayName("editing the template afterwards does not reach a product already copied from it (a snapshot)")
    void copyIsASnapshot() {
        WorkCodeCategory category = fixture.scenario().build().workCategory();
        ProductType type = aType();
        ProductTypeOperation blueprint = template(type, "Krimpovanje", category, 80);
        Product product = fixture.product("IT-Proizvod-" + System.nanoTime());

        CopyTypeOperationsRequest request = new CopyTypeOperationsRequest();
        request.setTargetProductId(product.getId());
        request.setProductTypeOperationIds(List.of(blueprint.getId()));
        operations.copyTypeOperationsToProduct(request);

        // Change the template after the copy.
        blueprint.setDefaultMinNorm(999);
        blueprint.setDefaultMaxNorm(999);
        blueprint.setOpName("Preimenovano");
        templateRepository.saveAndFlush(blueprint);

        Operation copied = operationRepository.findByProductIdAndArchivedAtIsNull(product.getId())
                .stream().filter(o -> o.getOpName().equals("Krimpovanje")).findFirst().orElseThrow();
        assertThat(copied.getMinNorm()).isEqualTo(80);
        assertThat(copied.getOpName()).isEqualTo("Krimpovanje");
    }
}
