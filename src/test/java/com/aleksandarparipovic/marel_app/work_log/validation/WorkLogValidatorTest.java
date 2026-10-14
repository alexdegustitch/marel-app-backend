package com.aleksandarparipovic.marel_app.work_log.validation;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The standalone consistency rules on a karton log: a standalone operation
 * carries no order and produces nothing, and the log must not claim otherwise.
 */
class WorkLogValidatorTest {

    private final WorkLogValidator validator = new WorkLogValidator();

    private static Operation standalone() {
        Operation operation = new Operation();
        operation.setStandalone(true);
        return operation;
    }

    private static Operation productOperation() {
        Operation operation = new Operation();
        operation.setProduct(new Product());
        return operation;
    }

    @Test
    void standaloneLogWithNoOrderAndNoQuantityPasses() {
        assertThatCode(() -> validator.validateStandaloneConsistency(standalone(), null, 0, 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateStandaloneConsistency(standalone(), null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void standaloneLogRefusesAnOrder() {
        assertThatThrownBy(() -> validator.validateStandaloneConsistency(standalone(), 7L, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne vezuje za nalog");
    }

    @Test
    void standaloneLogRefusesQuantityAndScrap() {
        assertThatThrownBy(() -> validator.validateStandaloneConsistency(standalone(), null, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("količinu ni škart");
        assertThatThrownBy(() -> validator.validateStandaloneConsistency(standalone(), null, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("količinu ni škart");
    }

    @Test
    void productOperationIsUntouchedByTheStandaloneRules() {
        assertThatCode(() -> validator.validateStandaloneConsistency(productOperation(), 7L, 5, 1))
                .doesNotThrowAnyException();
    }
}
