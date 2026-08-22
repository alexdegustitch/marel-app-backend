package com.aleksandarparipovic.marel_app.notification_delivery;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends through Brevo's SMTP relay.
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
 * receiving end for any domain Brevo is not authorised to send for.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.mail.username")
public class BrevoEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String defaultFromName;

    public BrevoEmailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.mail.from-name:Furlytics}") String defaultFromName
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
            helper.setTo(message.toAddress());
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);

            if (message.replyTo() != null && !message.replyTo().isBlank()) {
                helper.setReplyTo(message.replyTo());
            }

            mailSender.send(mime);
            log.info("[BrevoEmailSender] Sent to {} — '{}'", message.toAddress(), message.subject());
        } catch (Exception ex) {
            // Rethrown so the worker records the failure and schedules a retry;
            // swallowing it here would mark an unsent message as delivered.
            throw new IllegalStateException("Slanje e-pošte nije uspelo.", ex);
        }
    }

    private InternetAddress fromWithDisplayName(String fromName) throws UnsupportedEncodingException {
        String displayName = fromName == null || fromName.isBlank() ? defaultFromName : fromName;
        return new InternetAddress(fromAddress, displayName, StandardCharsets.UTF_8.name());
    }
}
