package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.order_email_view.OrderEmailState;
import com.aleksandarparipovic.marel_app.order_email_view.OrderEmailView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agreed markup, pinned: obrisano precrtano ({@code <del>}), dodato
 * podebljano ({@code <b>}), ostalo običnim tekstom — and the PDF's CLEAN mode
 * shows the order as it stands, with nothing marked and nothing removed.
 */
class OrderEmailHtmlRendererTest {

    private static OrderEmailView diffView() {
        return OrderEmailView.diff("190/2026",
                new OrderEmailState(
                        List.of(new OrderEmailState.Field("Kupac", "ENIA")),
                        List.of("03.09.2026. (500 kom)"),
                        List.of(
                                new OrderEmailState.Item("1", "Čaura 150", "500", null),
                                new OrderEmailState.Item("2", "Čaura 240", "50", "stara napomena"))),
                new OrderEmailState(
                        List.of(new OrderEmailState.Field("Kupac", "ENIA Grčka")),
                        List.of("10.09.2026. (500 kom)"),
                        List.of(
                                new OrderEmailState.Item("1", "Čaura 150", "500 + 50", null),
                                new OrderEmailState.Item("3", "Univerzalna čaura", "200", null))));
    }

    @Test
    @DisplayName("DIFF mode strikes the removed and bolds the added")
    void diffModeMarksChanges() {
        String html = OrderEmailHtmlRenderer.render(diffView(), OrderEmailHtmlRenderer.Mode.DIFF);

        // The edited field carries both readings.
        assertThat(html).contains("<del style=\"color:#868e96;text-decoration:line-through\">ENIA</del>");
        assertThat(html).contains("<b>ENIA Grčka</b>");

        // The changed quantity, the removed item, the added item.
        assertThat(html).contains("<del style=\"color:#868e96;text-decoration:line-through\">500</del>");
        assertThat(html).contains("<b>500 + 50</b>");
        assertThat(html).contains("Čaura 240");
        assertThat(html).contains("Univerzalna čaura");

        // The moved deadline reads as its old line struck and its new line bold.
        assertThat(html).contains("<b>10.09.2026. (500 kom)</b>");
        assertThat(html).contains("03.09.2026. (500 kom)</del>");
    }

    @Test
    @DisplayName("CLEAN mode shows only what stands — nothing struck, nothing removed")
    void cleanModeShowsOnlyTheCurrentOrder() {
        String html = OrderEmailHtmlRenderer.render(diffView(), OrderEmailHtmlRenderer.Mode.CLEAN);

        assertThat(html).doesNotContain("<del").doesNotContain("<b>");
        assertThat(html).contains("ENIA Grčka").doesNotContain(">ENIA<");
        // The removed line and the removed deadline are gone entirely.
        assertThat(html).doesNotContain("Čaura 240").doesNotContain("03.09.2026.");
        assertThat(html).contains("Univerzalna čaura").contains("10.09.2026. (500 kom)");
    }

    @Test
    @DisplayName("an unset field this save did not touch is not a row")
    void unsetUntouchedFieldIsHidden() {
        OrderEmailView view = OrderEmailView.of("1", new OrderEmailState(
                List.of(new OrderEmailState.Field("Napomena", null),
                        new OrderEmailState.Field("Kupac", "ENIA")),
                List.of(), List.of()));

        String html = OrderEmailHtmlRenderer.render(view, OrderEmailHtmlRenderer.Mode.DIFF);

        assertThat(html).doesNotContain("Napomena").contains("Kupac");
    }

    @Test
    @DisplayName("user text is escaped, never emitted as markup")
    void escapesUserText() {
        OrderEmailView view = OrderEmailView.of("1", new OrderEmailState(
                List.of(new OrderEmailState.Field("Napomena", "<script>alert(1)</script>")),
                List.of(),
                List.of(new OrderEmailState.Item("1", "Čaura <b>&", "5", null))));

        String html = OrderEmailHtmlRenderer.render(view, OrderEmailHtmlRenderer.Mode.DIFF);

        assertThat(html).doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("Čaura &lt;b&gt;&amp;");
    }

    @Test
    @DisplayName("kept lines stay numbered as the order reads without the removed one")
    void removedRowKeepsNoNumber() {
        OrderEmailView view = OrderEmailView.diff("1",
                new OrderEmailState(List.of(), List.of(), List.of(
                        new OrderEmailState.Item("1", "Prva", "1", null),
                        new OrderEmailState.Item("2", "Druga", "2", null))),
                new OrderEmailState(List.of(), List.of(), List.of(
                        new OrderEmailState.Item("2", "Druga", "2", null))));

        String html = OrderEmailHtmlRenderer.render(view, OrderEmailHtmlRenderer.Mode.DIFF);

        // The surviving line is line 1; the removed one shows a dash.
        assertThat(html).contains(">1</td>");
        assertThat(html).contains("—");
    }
}
