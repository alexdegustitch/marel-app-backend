package com.aleksandarparipovic.marel_app.notification_delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Turns a queued notification into the e-mail a person actually reads.
 *
 * <p>Kept apart from the sender so the wording and the transport can change
 * independently, and so composing can be tested without a mail server.
 *
 * <p>The HTML is intentionally plain and inline-styled: business inboxes strip
 * stylesheets, and most of these are read on a phone.
 */
@Component
public class NotificationEmailComposer {

    /**
     * Where the "open it" link points. The same property the OAuth flow already
     * uses to find the web app, so there is one answer to "where does this
     * installation live" rather than two that can drift.
     */
    private final String webAppUrl;

    private final String appName;

    /**
     * Entity type as stored on the event -> the route that shows it.
     *
     * <p>Registration requests are absent on purpose. The only registration mail
     * that goes out is the decision, and it goes to the applicant — who is either
     * not yet able to sign in or has just been refused. A deep link into the
     * administrators' review screen would be a door they cannot open.
     */
    private static final Map<String, String> ROUTE_BY_ENTITY = Map.of(
            "PRODUCTION_ORDER", "/admin/production-orders/",
            "MANUFACTURING_TIME_REQUEST", "/admin/requests"
    );

    public NotificationEmailComposer(
            @Value("${app.web-app-url:http://localhost:5123}") String webAppUrl,
            @Value("${app.mail.from-name:Furlytics}") String appName
    ) {
        this.webAppUrl = webAppUrl;
        this.appName = appName;
    }

    public EmailMessage compose(DeliveryBatchProcessor.PendingSend send) {
        return new EmailMessage(
                send.recipientEmail(),
                send.subject(),
                htmlBody(send),
                // A system event has no actor and keeps the application's own name.
                send.actorName() == null || send.actorName().isBlank() ? appName : send.actorName(),
                send.actorEmail()
        );
    }

    private String htmlBody(DeliveryBatchProcessor.PendingSend send) {
        String link = linkFor(send.entityType(), send.entityId());

        StringBuilder html = new StringBuilder()
                .append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;")
                .append("font-size:14px;color:#212529;line-height:1.55;max-width:520px\">")
                .append("<p style=\"font-size:16px;font-weight:600;margin:0 0 8px\">")
                .append(escape(send.subject()))
                .append("</p><p style=\"margin:0 0 16px\">")
                .append(escape(send.body()))
                .append("</p>");

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
