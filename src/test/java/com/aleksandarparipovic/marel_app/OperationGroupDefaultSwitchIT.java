package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationGroupService;
import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.operation.dto.OperationGroupDtos.OperationGroupCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationGroupDtos.OperationGroupDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationStepsRequest;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The group's default is a radio: handing it over must work in BOTH
 * directions. The partial unique index {@code ux_operations_one_group_default}
 * is not deferrable, so the switch has to clear the old flag in the database
 * before the new one lands — left to one flush, the two UPDATEs could meet
 * with both rows true, and the owner's click answered with a 500.
 */
@Transactional
class OperationGroupDefaultSwitchIT extends AbstractIntegrationTest {

    @Autowired private OperationGroupService groupService;
    @Autowired private OperationService operationService;
    @Autowired private OperationRepository operationRepository;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("the default hands over cleanly, in both directions")
    void defaultHandsOverBothWays() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Product product = fixture.product("IT-Proizvod-grupa");
        Operation first = fixture.operation(product, scenario.workCategory(), 40);
        Operation second = fixture.operation(product, scenario.workCategory(), 60);

        OperationGroupCreateRequest create = new OperationGroupCreateRequest();
        create.setProductId(product.getId());
        create.setName("IT-Grupa-presa");
        OperationGroupDto group = groupService.create(create);
        groupService.addMember(group.getId(), first.getId());
        groupService.addMember(group.getId(), second.getId());

        // Forward: from the first member (default by arrival) to the second.
        groupService.setDefault(group.getId(), second.getId());
        assertThat(defaultOf(group.getId())).isEqualTo(second.getId());

        // Backward: to the member with the SMALLER id — the direction where a
        // single unordered flush could write the new TRUE before the old FALSE.
        groupService.setDefault(group.getId(), first.getId());
        assertThat(defaultOf(group.getId())).isEqualTo(first.getId());

        // Setting the one already default changes nothing and still succeeds.
        groupService.setDefault(group.getId(), first.getId());
        assertThat(defaultOf(group.getId())).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("changing one member's step moves the whole group")
    void stepMovesTheWholeGroup() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Product product = fixture.product("IT-Proizvod-korak");
        Operation first = fixture.operation(product, scenario.workCategory(), 40);
        Operation second = fixture.operation(product, scenario.workCategory(), 60);
        Operation loner = fixture.operation(product, scenario.workCategory(), 80);

        OperationGroupCreateRequest create = new OperationGroupCreateRequest();
        create.setProductId(product.getId());
        create.setName("IT-Grupa-korak");
        OperationGroupDto group = groupService.create(create);
        groupService.addMember(group.getId(), first.getId());
        groupService.addMember(group.getId(), second.getId());

        operationService.changeSortOrder(first.getId(), 3);

        // Members share the step by invariant — the PATCH on one moved both.
        assertThat(operationRepository.findByOperationGroup_Id(group.getId()))
                .extracting(Operation::getSortOrder)
                .containsExactly(3, 3);

        // An operation outside the group moves alone, and null unplaces it.
        operationService.changeSortOrder(loner.getId(), 3);
        assertThat(operationRepository.findById(loner.getId()).orElseThrow().getSortOrder()).isEqualTo(3);
        operationService.changeSortOrder(loner.getId(), null);
        assertThat(operationRepository.findById(loner.getId()).orElseThrow().getSortOrder()).isNull();
    }

    @Test
    @DisplayName("the drawer's one-transaction reorder: groups move whole, contradictions refuse")
    void bulkReorderRespectsGroups() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        Product product = fixture.product("IT-Proizvod-redosled");
        Operation a = fixture.operation(product, scenario.workCategory(), 40);
        Operation b = fixture.operation(product, scenario.workCategory(), 60);
        Operation c = fixture.operation(product, scenario.workCategory(), 80);

        OperationGroupCreateRequest create = new OperationGroupCreateRequest();
        create.setProductId(product.getId());
        create.setName("IT-Grupa-redosled");
        OperationGroupDto group = groupService.create(create);
        groupService.addMember(group.getId(), a.getId());
        groupService.addMember(group.getId(), b.getId());

        // Naming ONE member places the whole group; c lands beside it (parallel).
        operationService.reorderSteps(new OperationStepsRequest(product.getId(), java.util.List.of(
                new OperationStepsRequest.Step(a.getId(), 2),
                new OperationStepsRequest.Step(c.getId(), 2))));
        assertThat(operationRepository.findById(b.getId()).orElseThrow().getSortOrder()).isEqualTo(2);
        assertThat(operationRepository.findById(c.getId()).orElseThrow().getSortOrder()).isEqualTo(2);

        // Two members of one group on different steps is a contradiction.
        assertThatThrownBy(() -> operationService.reorderSteps(
                new OperationStepsRequest(product.getId(), java.util.List.of(
                        new OperationStepsRequest.Step(a.getId(), 1),
                        new OperationStepsRequest.Step(b.getId(), 3)))))
                .isInstanceOf(ConflictException.class);

        // An operation of ANOTHER product never slips into this product's order.
        Operation foreign = fixture.operation(scenario.workCategory(), 50);
        assertThatThrownBy(() -> operationService.reorderSteps(
                new OperationStepsRequest(product.getId(), java.util.List.of(
                        new OperationStepsRequest.Step(foreign.getId(), 1)))))
                .isInstanceOf(ConflictException.class);

        // Null unplaces, and an operation the request skips keeps its step.
        operationService.reorderSteps(new OperationStepsRequest(product.getId(), java.util.List.of(
                new OperationStepsRequest.Step(c.getId(), null))));
        assertThat(operationRepository.findById(c.getId()).orElseThrow().getSortOrder()).isNull();
        assertThat(operationRepository.findById(a.getId()).orElseThrow().getSortOrder()).isEqualTo(2);
    }

    private Long defaultOf(Long groupId) {
        var defaults = operationRepository.findByOperationGroup_Id(groupId).stream()
                .filter(Operation::isGroupDefault)
                .toList();
        assertThat(defaults).hasSize(1);
        return defaults.getFirst().getId();
    }
}
