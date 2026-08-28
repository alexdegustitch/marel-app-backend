package com.aleksandarparipovic.marel_app.notification_delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the recipient actually sees.
 *
 * <p>The two properties worth pinning down: the message names the person whose
 * action caused it without claiming they sent it, and text that came from a user
 * cannot become markup in somebody else's mail client.
 */
class NotificationEmailComposerTest {

    private final NotificationEmailComposer composer =
            new NotificationEmailComposer("https://app.furlytics.com", "Furlytics");

    private DeliveryBatchProcessor.PendingSend send(
            String subject, String body, String actorName, String actorEmail,
            String entityType, Long entityId
    ) {
        return new DeliveryBatchProcessor.PendingSend(
                1L, NotificationChannel.EMAIL, List.of("primalac@marel.rs"),
                subject, body, actorName, actorEmail, entityType, entityId,
                null, null, null);
    }

    @Test
    @DisplayName("an employee's action puts their name on the mail and their address on Reply-To")
    void actorBecomesSenderIdentity() {
        EmailMessage message = composer.compose(send(
                "Promenjen rok isporuke", "Nalog N-12: rok pomeren.",
                "Maja Vučetić", "maja@marel.rs", "PRODUCTION_ORDER", 42L));

        assertThat(message.fromName()).isEqualTo("Maja Vučetić");
        assertThat(message.replyTo()).isEqualTo("maja@marel.rs");
        // Named in the body too: the From line alone would read as if she typed it.
        assertThat(message.htmlBody()).contains("Maja Vučetić");
    }

    @Test
    @DisplayName("a system event falls back to the application's own name")
    void systemEventUsesAppName() {
        EmailMessage message = composer.compose(send(
                "Nalog je isporučen", "Nalog N-12 je isporučen.",
                null, null, "PRODUCTION_ORDER", 42L));

        assertThat(message.fromName()).isEqualTo("Furlytics");
        assertThat(message.replyTo()).isNull();
    }

    @Test
    @DisplayName("the link points at the entity through the hash router")
    void linksToTheEntity() {
        EmailMessage message = composer.compose(send(
                "Promenjen rok isporuke", "Nalog N-12.",
                null, null, "PRODUCTION_ORDER", 42L));

        assertThat(message.htmlBody())
                .contains("https://app.furlytics.com/#/admin/production-orders/42");
    }

    @Test
    @DisplayName("an unknown entity type simply gets no link")
    void unknownEntityHasNoLink() {
        EmailMessage message = composer.compose(send(
                "Nešto", "Poruka.", null, null, "SOMETHING_ELSE", 7L));

        assertThat(message.htmlBody()).doesNotContain("<a href");
    }

    @Test
    @DisplayName("user-supplied text is escaped, never emitted as markup")
    void escapesUserText() {
        EmailMessage message = composer.compose(send(
                "Nalog <script>alert(1)</script>", "Rok & rok.",
                "O'Brien <hack>", "x@y.rs", "PRODUCTION_ORDER", 1L));

        assertThat(message.htmlBody())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("Rok &amp; rok.")
                .contains("O'Brien &lt;hack&gt;");
    }
}
