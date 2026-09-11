package com.aleksandarparipovic.marel_app.mailing_list.dto;

import com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility;

/**
 * A mailing list as a colleague's profile names it: just enough to say the person
 * is on it. Deliberately slimmer than {@link MailingListResponse} — no owner, no
 * member count, nothing the profile has no business showing about somebody else's
 * list. Visibility rides along so the section can quietly mark a GLOBAL list.
 */
public record UserMailingListDto(
        Long id,
        String name,
        MailingListVisibility visibility
) {
}
