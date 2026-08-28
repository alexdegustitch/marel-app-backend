package com.aleksandarparipovic.marel_app.notification_delivery;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends through Postmark's SMTP relay.
 *
 * <p><b>Registered only when {@code spring.mail.username} is set.</b> That is what
 * keeps local machines and the test suite on {@link LoggingEmailSender} without a
 * flag anyone has to remember to flip — no credentials, no sending, no quota
 * spent. On the server the credentials exist, this bean appears, and
 * {@code @ConditionalOnMissingBean} in {@link NotificationDeliveryConfig} steps
 * aside on its own.
 *
 * <p>The envelope sender is always the configured, verified address. The person
 * whose action caused the notification appears as the DISPLAY NAME and as
 * Reply-To — so the inbox reads as a message from that colleague, replies reach
 * them, and the DKIM signature still belongs to a domain this installation
 * controls. Putting their real address in From would fail authentication at the
 * receiving end for any domain the relay is not authorised to send for.
 *
 * <p>Nothing here is Postmark-specific except {@code X-PM-KeepID} — the transport
 * is plain SMTP, so moving to another relay is four properties and a rename.
 */
@Slf4j
@Component
/*
 * Set AND non-empty. `@ConditionalOnProperty` asks only whether the property is
 * PRESENT, and `application.properties` gives it an empty default — so this bean
 * registered on every machine without credentials, which is the exact opposite of
 * what the block above promises. Nothing revealed it until an integration test
 * committed a transaction that fires an after-commit mail: the suite then opened
 * SMTP connections to the relay and posted to invented addresses.
 */
@ConditionalOnExpression("!'${spring.mail.username:}'.trim().isEmpty()")
public class PostmarkEmailSender implements EmailSender {

    /**
     * Postmark replaces the Message-ID of every SMTP message unless this header
     * says otherwise. Without it our own id never reaches the recipient, the id
     * stored on the delivery row matches nothing, and the next mail's In-Reply-To
     * points at a message that does not exist — every notification would arrive
     * as its own conversation. Verified against a live send before this was
     * written; see V14.
     */
    private static final String KEEP_ID_HEADER = "X-PM-KeepID";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String defaultFromName;

    public PostmarkEmailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.mail.from-name:Dooklytics}") String defaultFromName
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.defaultFromName = defaultFromName;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, false, StandardCharsets.UTF_8.name());

            helper.setFrom(fromWithDisplayName(message.fromName()));
            helper.setTo(message.toAddresses().toArray(new String[0]));
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);

            if (message.replyTo() != null && !message.replyTo().isBlank()) {
                helper.setReplyTo(message.replyTo());
            }

            applyThreadingHeaders(mime, message);

            mailSender.send(mime);
            log.info("[PostmarkEmailSender] Sent to {} recipient(s) — '{}'",
                    message.toAddresses().size(), message.subject());
        } catch (Exception ex) {
            // Rethrown so the worker records the failure and schedules a retry;
            // swallowing it here would mark an unsent message as delivered.
            throw new IllegalStateException("Slanje e-pošte nije uspelo: " + reason(ex), ex);
        }
    }

    /**
     * The provider's own words, put where they will survive.
     *
     * <p>ErrorSanitizer keeps only the TOP exception's message, so wrapping the
     * cause without naming it here left `last_error` reading "Slanje e-pošte nije
     * uspelo." on every failure — identical whether the sender address was
     * unverified, the recipient outside an approved domain, or the credentials
     * wrong. The distinction is the entire diagnostic value, and it is not a
     * secret: SMTP refusals name the rule that was broken, not the password.
     */
    private static String reason(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : " — " + message.strip());
    }

    /**
     * Set AFTER the helper has populated the message: {@code setText} rebuilds
     * the MIME body, and headers written before it are lost with the parts they
     * were attached to.
     *
     * <p>Relies on Spring's {@code JavaMailSenderImpl}, which reads the
     * Message-ID before {@code saveChanges()} and writes it back afterwards.
     * Plain JavaMail does NOT: {@code saveChanges()} regenerates the header and
     * silently discards whatever was there. Sending through anything other than
     * Spring's sender therefore needs a {@code MimeMessage} subclass that
     * overrides {@code updateMessageID()}.
     */
    private void applyThreadingHeaders(MimeMessage mime, EmailMessage message) throws Exception {
        if (message.messageId() == null || message.messageId().isBlank()) {
            return;
        }

        mime.setHeader("Message-ID", message.messageId());
        mime.setHeader(KEEP_ID_HEADER, "true");

        if (message.inReplyTo() != null && !message.inReplyTo().isBlank()) {
            mime.setHeader("In-Reply-To", message.inReplyTo());
        }
        if (message.references() != null && !message.references().isBlank()) {
            mime.setHeader("References", message.references());
        }
    }

    private InternetAddress fromWithDisplayName(String fromName) throws UnsupportedEncodingException {
        String displayName = fromName == null || fromName.isBlank() ? defaultFromName : fromName;
        return new InternetAddress(fromAddress, displayName, StandardCharsets.UTF_8.name());
    }
}
