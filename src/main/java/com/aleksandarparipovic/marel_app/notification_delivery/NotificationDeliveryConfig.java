package com.aleksandarparipovic.marel_app.notification_delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationDeliveryConfig {

    /**
     * Only registered when the application context has no other EmailSender, so
     * adding a real provider adapter is enough to take over — nothing here needs
     * editing.
     */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }
}
