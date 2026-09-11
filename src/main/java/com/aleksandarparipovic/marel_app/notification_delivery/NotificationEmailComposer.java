package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.order_email_view.OrderEmailView;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns a queued notification into the e-mail a person actually reads.
 *
 * <p>Kept apart from the sender so the wording and the transport can change
 * independently, and so composing can be tested without a mail server.
 *
 * <p>The HTML is intentionally plain and inline-styled: business inboxes strip
 * stylesheets, and most of these are read on a phone.
 *
 * <p>An order event whose payload carries an {@code orderView} gets the full
 * treatment: the whole order as a table with this save's changes struck
 * through and bolded, and the order's PDF document attached. Everything else
 * keeps the original one-paragraph shape.
 */
@Slf4j
@Component
public class NotificationEmailComposer {

    /**
     * Payload trees were written by {@link
     * com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher} with a
     * plain Jackson 2 mapper; the same kind reads them back. Deliberately not
     * the auto-configured bean, for the reason documented there.
     */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /**
     * Where the "open it" link points. The same property the OAuth flow already
     * uses to find the web app, so there is one answer to "where does this
     * installation live" rather than two that can drift.
     */
    private final String webAppUrl;

    private final String appName;

    private final OrderPdfRenderer pdfRenderer;

    /**
     * Entity type as stored on the event -> the route that shows it.
     *
     * <p>Registration requests are absent on purpose. The only registration mail
     * that goes out is the decision, and it goes to the applicant — who is either
     * not yet able to sign in or has just been refused. A deep link into the
     * administrators' review screen would be a door they cannot open.
     */
    private static final Map<String, String> ROUTE_BY_ENTITY = Map.of(
            "PRODUCTION_ORDER", "/app/production-orders/",
            "SAMPLE_ORDER", "/app/sample-orders/",
            "MANUFACTURING_TIME_REQUEST", "/app/requests"
    );

    public NotificationEmailComposer(
            @Value("${app.web-app-url:http://localhost:5123}") String webAppUrl,
            @Value("${app.mail.from-name:Furlytics}") String appName,
            OrderPdfRenderer pdfRenderer
    ) {
        this.webAppUrl = webAppUrl;
        this.appName = appName;
        this.pdfRenderer = pdfRenderer;
    }

    public EmailMessage compose(DeliveryBatchProcessor.PendingSend send) {
        OrderEmailView orderView = orderViewOf(send.payload());

        return new EmailMessage(
                send.recipientEmails(),
                send.subject(),
                htmlBody(send, orderView),
                // A system event has no actor and keeps the application's own name.
                send.actorName() == null || send.actorName().isBlank() ? appName : send.actorName(),
                send.actorEmail(),
                // Carried through untouched. These were decided when the delivery
                // row was queued, and nothing here may second-guess them: the
                // stored chain and the sent headers have to agree, or the next
                // mail replies to a message that was never sent this way.
                send.messageId(),
                send.inReplyTo(),
                send.references(),
                attachments(send, orderView)
        );
    }

    private String htmlBody(DeliveryBatchProcessor.PendingSend send, OrderEmailView orderView) {
        String link = linkFor(send.entityType(), send.entityId());

        StringBuilder html = new StringBuilder()
                .append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;")
                .append("font-size:14px;color:#212529;line-height:1.55;max-width:")
                .append(orderView == null ? "520px" : "680px")
                .append("\">");

        if (orderView != null) {
            html.append("<p style=\"margin:0 0 12px\">Dobar dan,</p>");
        }

        html.append("<p style=\"font-size:16px;font-weight:600;margin:0 0 8px\">")
                .append(escape(send.subject()))
                .append("</p><p style=\"margin:0 0 16px\">")
                .append(escape(introFor(send, orderView)))
                .append("</p>");

        if (orderView != null) {
            html.append(OrderEmailHtmlRenderer.render(
                    orderView, OrderEmailHtmlRenderer.Mode.DIFF));
        }

        if (send.actorName() != null && !send.actorName().isBlank()) {
            // Says plainly that a person caused this and the application sent it.
            // The From line shows their name, so without this the message could
            // read as something they typed and sent themselves.
            html.append("<p style=\"margin:0 0 16px;color:#868e96\">Izmenu je unela/uneo: ")
                    .append(escape(send.actorName()))
                    .append("</p>");
        }

        if (link != null) {
            html.append("<p style=\"margin:0 0 16px\"><a href=\"").append(link)
                    .append("\" style=\"color:#4c6ef5\">Otvorite u aplikaciji</a></p>");
        }

        return html.append("<p style=\"margin:0;color:#adb5bd;font-size:12px\">")
                .append("Ovu poruku je automatski poslala aplikacija ").append(escape(appName))
                .append(".</p></div>")
                .toString();
    }

    /**
     * The sentence above the table.
     *
     * <p>On an edit, the event's own message is the change list spelled out —
     * right for the in-app feed, redundant above a table that shows the same
     * changes marked. The mail explains the marking instead. Every other event
     * keeps its stored message.
     */
    private static String introFor(DeliveryBatchProcessor.PendingSend send, OrderEmailView orderView) {
        boolean edited = send.eventType() == OutboxEventType.PRODUCTION_ORDER_UPDATED
                || send.eventType() == OutboxEventType.SAMPLE_ORDER_UPDATED;

        if (orderView == null || !edited) {
            return send.body();
        }
        return "Nalog je izmenjen — uklonjeno je precrtano, dodato ili promenjeno je podebljano. "
                + "Nalog sada glasi ovako:";
    }

    /**
     * The order's PDF document, when this event carries the order at all.
     *
     * <p>A failed render is logged and the mail goes out without the file: the
     * attachment is a convenience copy of what the body already shows, and a
     * PDF bug must not silence the conversation itself.
     */
    private List<EmailMessage.Attachment> attachments(
            DeliveryBatchProcessor.PendingSend send, OrderEmailView orderView) {
        if (orderView == null) {
            return List.of();
        }
        try {
            byte[] pdf = pdfRenderer.render(titleOf(send), orderView);
            return List.of(new EmailMessage.Attachment(
                    fileNameFor(orderView.code()), "application/pdf", pdf));
        } catch (RuntimeException ex) {
            log.error("[NotificationEmailComposer] PDF za isporuku {} nije generisan",
                    send.deliveryId(), ex);
            return List.of();
        }
    }

    /** The conversation's subject without the reply prefix — the document's title. */
    private static String titleOf(DeliveryBatchProcessor.PendingSend send) {
        String subject = send.subject() == null ? "" : send.subject();
        return subject.startsWith("Re: ") ? subject.substring(4) : subject;
    }

    /** "Nalog-190-2026.pdf" — the code, spelled so every filesystem accepts it. */
    private static String fileNameFor(String orderCode) {
        String safe = orderCode == null || orderCode.isBlank()
                ? "nalog"
                : orderCode.replaceAll("[^0-9A-Za-zčćšžđČĆŠŽĐ-]+", "-");
        return "Nalog-" + safe + ".pdf";
    }

    private static OrderEmailView orderViewOf(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("orderView")) {
            return null;
        }
        try {
            return PAYLOAD_MAPPER.treeToValue(payload.get("orderView"), OrderEmailView.class);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            // A malformed view must not lose the notification: the mail falls
            // back to the plain one-paragraph shape it always had.
            log.error("[NotificationEmailComposer] orderView nije mogao da se pročita", ex);
            return null;
        }
    }

    private String linkFor(String entityType, Long entityId) {
        String route = ROUTE_BY_ENTITY.get(entityType);
        if (route == null) {
            return null;
        }
        // HashRouter: the path has to live after the "#" or the app never sees it.
        String target = route.endsWith("/") ? route + entityId : route;
        return webAppUrl + "/#" + target;
    }

    /**
     * Notification text carries names and order codes typed by users. Interpolated
     * raw, a stray angle bracket would break the layout — and worse, the text would
     * be markup the recipient's client executes.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
