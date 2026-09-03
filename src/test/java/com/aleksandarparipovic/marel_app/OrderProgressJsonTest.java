package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OutOfScopeWork;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ProductProgress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an operation's progress actually looks like on the wire.
 *
 * <p>Its percentage, the pieces that count and whether it was overproduced are
 * DERIVED — computed by methods rather than held as record components. Jackson
 * serialises a record from its components, so without saying otherwise those
 * three never leave the server, and the screen draws "no razrada" over an
 * operation whose razrada is right beside it.
 */
class OrderProgressJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("an operation reports its percentage, not only the pieces behind it")
    void operationCarriesItsPercentage() throws Exception {
        OperationProgress operation = new OperationProgress(
                1L, "Operacija 1", BigDecimal.ONE, 2000, 356, 0);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(operation));

        assertThat(json.has("percent")).as("percent is on the wire: %s", json).isTrue();
        assertThat(json.get("percent").decimalValue()).isEqualByComparingTo("17.8");
        assertThat(json.get("countedPieces").asLong()).isEqualTo(356);
        assertThat(json.get("overproduced").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the whole answer keeps its figures down to the operation the screen draws")
    void theWholeTreeSurvivesSerialisation() throws Exception {
        OperationProgress operation = new OperationProgress(
                1L, "Operacija 1", BigDecimal.ONE, 2000, 356, 0);
        ProductProgress product = new ProductProgress(
                2L, "Kućište pumpe", List.of(314L), 2000, 356,
                new BigDecimal("17.8"), 1L, "Operacija 1", List.of(operation));
        OrderProgress progress = new OrderProgress(
                44L, true, 1, 0, 11000, 356, 356, 0,
                new BigDecimal("3.2"), List.of(product),
                List.of(new OutOfScopeWork(3L, "Operacija 3", 2L, "Kućište pumpe", 622)));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(progress));

        assertThat(json.get("percent").decimalValue()).isEqualByComparingTo("3.2");
        JsonNode line = json.get("products").get(0);
        assertThat(line.get("percent").decimalValue()).isEqualByComparingTo("17.8");
        assertThat(line.get("bottleneckOperationId").asLong()).isEqualTo(1);

        // The one that was missing: an operation's own figure, three levels down.
        JsonNode operationJson = line.get("operations").get(0);
        assertThat(operationJson.hasNonNull("percent"))
                .as("the operation the screen draws: %s", operationJson)
                .isTrue();
        assertThat(operationJson.get("percent").decimalValue()).isEqualByComparingTo("17.8");

        assertThat(json.get("outOfScope").get(0).get("donePieces").asLong()).isEqualTo(622);
    }
}
