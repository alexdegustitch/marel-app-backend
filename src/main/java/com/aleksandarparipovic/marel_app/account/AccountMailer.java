package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.notification_delivery.EmailMessage;
import com.aleksandarparipovic.marel_app.notification_delivery.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The two letters an address change sends.
 *
 * <p>Both go out AFTER COMMIT, never inside the transaction that wrote the row.
 * Two reasons, and both have bitten this kind of code before: a mail provider
 * that takes four seconds would hold a database connection for four seconds, and
 * a provider that fails would roll back a change the person has already been told
 * succeeded — or, worse, send the code for a request that then vanished.
 *
 * <p>A failed send is therefore NOT a failed change. The code is simply never
 * read, the request times out, and the person starts again. That is the right
 * failure: nothing is half-applied.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountMailer {

    private final EmailSender emailSender;

    @Value("${app.mail.from-name:Furlytics}")
    private String appName;

    /** Carries the code in memory only — it is stored hashed and cannot be re-read. */
    public record CodeIssued(String toAddress, String recipientName, String code, int validMinutes) {
    }

    public record ChangeCompleted(String oldAddress, String recipientName, String newAddress) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCodeIssued(CodeIssued event) {
        send(new EmailMessage(
                event.toAddress(),
                "Potvrda nove e-adrese",
                codeBody(event),
                appName,
                null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChangeCompleted(ChangeCompleted event) {
        /*
         * TO THE OLD ADDRESS, on purpose. The new one already knows — it just
         * confirmed. This is the only warning the rightful owner gets if somebody
         * else made the change, and it is the reason the notice exists at all.
         */
        send(new EmailMessage(
                event.oldAddress(),
                "Vaša e-adresa je promenjena",
                completedBody(event),
                appName,
                null));
    }

    private void send(EmailMessage message) {
        try {
            emailSender.send(message);
        } catch (RuntimeException ex) {
            // Never rethrow: this runs after commit, so throwing cannot undo
            // anything and would only surface as an error on a request that
            // already succeeded.
            log.error("Account mail '{}' could not be sent", message.subject(), ex);
        }
    }

    private String codeBody(CodeIssued event) {
        return wrap(
                "<p style=\"margin:0 0 16px\">" + escape(greeting(event.recipientName())) + "</p>"
                        + "<p style=\"margin:0 0 12px\">Zatražena je promena e-adrese naloga na ovu adresu. "
                        + "Unesite ovaj kod u aplikaciji da biste je potvrdili:</p>"
                        + "<p style=\"font-size:30px;font-weight:700;letter-spacing:6px;margin:0 0 12px\">"
                        + escape(event.code()) + "</p>"
                        + "<p style=\"margin:0 0 16px;color:#6c757d\">Kod važi " + event.validMinutes()
                        + " minuta.</p>"
                        + "<p style=\"margin:0;color:#6c757d\">Ako ovo niste tražili, ne radite ništa — "
                        + "adresa naloga se neće promeniti.</p>");
    }

    private String completedBody(ChangeCompleted event) {
        return wrap(
                "<p style=\"margin:0 0 16px\">" + escape(greeting(event.recipientName())) + "</p>"
                        + "<p style=\"margin:0 0 12px\">E-adresa vašeg naloga promenjena je na "
                        + "<strong>" + escape(event.newAddress()) + "</strong>.</p>"
                        + "<p style=\"margin:0 0 16px\">Od sada se prijavljujete tom adresom, i obaveštenja "
                        + "stižu na nju.</p>"
                        + "<p style=\"margin:0;color:#b02a37\">Ako ovu promenu niste napravili vi, odmah se "
                        + "javite administratoru — neko drugi ima pristup vašem nalogu.</p>");
    }

    private String greeting(String recipientName) {
        return recipientName == null || recipientName.isBlank() ? "Poštovani," : "Poštovani " + recipientName + ",";
    }

    /** Inline styles only: business inboxes strip stylesheets, as the notification mail already assumes. */
    private String wrap(String inner) {
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:14px;"
                + "color:#212529;line-height:1.55;max-width:520px\">" + inner + "</div>";
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
