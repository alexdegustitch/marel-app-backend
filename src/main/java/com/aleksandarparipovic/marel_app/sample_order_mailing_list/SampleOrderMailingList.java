package com.aleksandarparipovic.marel_app.sample_order_mailing_list;

import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Records that a mailing list was SELECTED for a sample order.
 *
 * <p>Intent only — the recipients it produced live in sample_order_recipients
 * and are independent from this row onwards. A surrogate id (rather than a
 * composite key) because audit_trigger_fn records NEW.id; uniqueness is enforced
 * by uq_sample_order_mailing_lists.
 */
@Entity
@Table(name = "sample_order_mailing_lists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleOrderMailingList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sample_order_id", nullable = false, updatable = false)
    private SampleOrder sampleOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mailing_list_id", nullable = false, updatable = false)
    private MailingList mailingList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "added_by", nullable = false, updatable = false)
    private User addedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
