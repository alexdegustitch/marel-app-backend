package com.aleksandarparipovic.marel_app.mailing_list_access;

import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * An explicit grant letting one user use one SHARED mailing list.
 *
 * <p>Exists because this codebase has no generic resource-permission mechanism —
 * authorization is role-based only, and roles cannot express "this person may use
 * that particular list".
 */
@Entity
@Table(name = "mailing_list_access")
@IdClass(MailingListAccessId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailingListAccess {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mailing_list_id", nullable = false)
    private MailingList mailingList;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "granted_by", nullable = false)
    private User grantedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
