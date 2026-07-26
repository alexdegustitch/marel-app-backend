package com.aleksandarparipovic.marel_app.mailing_list.dto;

import java.time.OffsetDateTime;

public record MailingListMemberResponse(
        Long id,
        Long userId,
        String externalEmail,
        /** The address this member currently resolves to. */
        String effectiveEmail,
        String displayName,
        boolean archived,
        OffsetDateTime createdAt
) {
}
