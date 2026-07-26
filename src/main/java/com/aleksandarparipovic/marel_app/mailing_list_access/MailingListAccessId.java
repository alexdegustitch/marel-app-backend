package com.aleksandarparipovic.marel_app.mailing_list_access;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite key for a shared-list grant: one row per (list, user). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MailingListAccessId implements Serializable {
    private Long mailingList;
    private Long user;
}
