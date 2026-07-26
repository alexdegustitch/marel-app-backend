package com.aleksandarparipovic.marel_app.mailing_list.dto;

import com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility;

import java.time.OffsetDateTime;

public record MailingListResponse(
        Long id,
        String name,
        String description,
        Long ownerUserId,
        String ownerName,
        MailingListVisibility visibility,
        int activeMemberCount,
        boolean archived,
        OffsetDateTime createdAt
) {
}
