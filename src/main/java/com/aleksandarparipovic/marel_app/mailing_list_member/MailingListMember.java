package com.aleksandarparipovic.marel_app.mailing_list_member;

import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * One recipient on a mailing list: EITHER an application user OR an external
 * email address, never both and never neither (enforced by
 * chk_mailing_list_members_exactly_one_source).
 *
 * <p>A user member intentionally does NOT snapshot the user's address — if they
 * change their email, the membership follows the person. Snapshotting happens
 * later, at production-order level, where history must be frozen.
 */
@Entity
@Table(name = "mailing_list_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailingListMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mailing_list_id", nullable = false, updatable = false)
    private MailingList mailingList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    /** Stored already lower-cased; the database rejects anything else. */
    @Column(name = "external_email", length = 320, updatable = false)
    private String externalEmail;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** The address this member resolves to right now, lower-cased. */
    public String effectiveEmail() {
        if (externalEmail != null) {
            return externalEmail;
        }
        return user == null || user.getEmailAddress() == null
                ? null
                : user.getEmailAddress().trim().toLowerCase(Locale.ROOT);
    }

    public String effectiveName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return user == null ? null : user.getFullName();
    }
}
