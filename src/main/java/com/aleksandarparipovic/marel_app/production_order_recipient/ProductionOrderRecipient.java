package com.aleksandarparipovic.marel_app.production_order_recipient;

import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One address a production order is (or was) sent to.
 *
 * <p>This is a SNAPSHOT, not a view over mailing lists. Once written it stops
 * following the list it came from: editing that list later never rewrites this
 * row. Email for the order is always sent from these rows.
 */
@Entity
@Table(name = "production_order_recipients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false, updatable = false)
    private ProductionOrder productionOrder;

    /** Present when the recipient is an application user; the email is still snapshotted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    /**
     * The address actually used, lower-cased, always populated — even when user_id
     * is set, so history survives the user changing their email.
     */
    @Column(name = "recipient_email", nullable = false, length = 320, updatable = false)
    private String recipientEmail;

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private RecipientSourceType sourceType;

    /** Set only for MAILING_LIST; names the first list that contributed this address. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_mailing_list_id", updatable = false)
    private MailingList sourceMailingList;

    /** NULL for SYSTEM recipients, which have no human author. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", updatable = false)
    private User addedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private User removedBy;

    public boolean isActive() {
        return removedAt == null;
    }

    /** Removal is an archive and is always attributable. */
    public void remove(User actor) {
        if (removedAt != null) {
            return;
        }
        this.removedAt = OffsetDateTime.now();
        this.removedBy = actor;
    }
}
