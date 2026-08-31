package com.aleksandarparipovic.marel_app.sample_order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status of a sample order is FREE TEXT in the database, not an enum.
 *
 * <p>Which makes reading it a real question rather than a formality: a row
 * written by hand, or by a script, can say {@code 'CLOSED'} or {@code ' closed'}
 * and mean exactly what the application means. Reading it strictly would show
 * such an order as still open — offering an edit the server then refuses, and
 * showing a countdown for work that is finished.
 */
class SampleOrderStatusTest {

    @Test
    @DisplayName("the value the application writes reads as closed")
    void closedIsClosed() {
        assertThat(SampleOrderStatus.isClosed(SampleOrderStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("a value written by hand in another case still reads as closed")
    void caseAndPaddingDoNotHideAClosedOrder() {
        assertThat(SampleOrderStatus.isClosed("CLOSED")).isTrue();
        assertThat(SampleOrderStatus.isClosed("Closed")).isTrue();
        assertThat(SampleOrderStatus.isClosed("  closed  ")).isTrue();
    }

    @Test
    @DisplayName("an open order, an unknown value and a missing one all read as not closed")
    void everythingElseIsOpen() {
        assertThat(SampleOrderStatus.isClosed(SampleOrderStatus.CREATED)).isFalse();
        assertThat(SampleOrderStatus.isClosed("u izradi")).isFalse();
        assertThat(SampleOrderStatus.isClosed(null)).isFalse();
        assertThat(SampleOrderStatus.isClosed("")).isFalse();
    }

    @Test
    @DisplayName("normalising gives back the form this application writes")
    void normalizeLowerCasesAndDefaults() {
        assertThat(SampleOrderStatus.normalize("CLOSED")).isEqualTo(SampleOrderStatus.CLOSED);
        assertThat(SampleOrderStatus.normalize(" Created ")).isEqualTo(SampleOrderStatus.CREATED);
        // A missing status is a new order, not an error: 'created' is the column's
        // own default, so the two agree.
        assertThat(SampleOrderStatus.normalize(null)).isEqualTo(SampleOrderStatus.CREATED);
        assertThat(SampleOrderStatus.normalize("   ")).isEqualTo(SampleOrderStatus.CREATED);
    }
}
