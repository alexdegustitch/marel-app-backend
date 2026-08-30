package com.aleksandarparipovic.marel_app.sample_order_email_thread;

import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The one e-mail conversation belonging to a sample order.
 *
 * <p>Holds what a mail client needs to file later messages under the first one:
 * the frozen subject and the chain of Message-IDs already sent. Every mail about
 * this order names {@link #lastMessageId} as its parent and carries
 * {@link #referencesChain}, which is what makes the client show a conversation
 * rather than a pile of unrelated notifications.
 *
 * <p>One row per order — enforced by {@code uq_soet_sample_order}, not by hope.
 * See V14 for why per-recipient threads would fracture the moment somebody
 * replies to all.
 */
@Entity
@Table(name = "sample_order_email_threads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleOrderEmailThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sample_order_id", nullable = false, updatable = false)
    private SampleOrder sampleOrder;

    /**
     * Without "Re:", and deliberately never updated after the first message.
     * Clients weigh the subject alongside References when grouping, so an edited
     * subject can split a conversation whose headers are otherwise perfect.
     */
    @Column(name = "subject_base", nullable = false, length = 255, updatable = false)
    private String subjectBase;

    @Column(name = "root_message_id", nullable = false, length = 255, updatable = false)
    private String rootMessageId;

    /** What the next message sets as In-Reply-To. */
    @Column(name = "last_message_id", nullable = false, length = 255)
    private String lastMessageId;

    /** Every id sent so far, space separated, oldest first — the header verbatim. */
    @Column(name = "references_chain", nullable = false)
    private String referencesChain;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * Files a newly assigned id as the newest message in this conversation.
     *
     * <p>The chain only ever grows. A client needs to find just ONE shared
     * ancestor to group two messages, but keeping the full history is what lets
     * the thread survive somebody replying from Outlook halfway through.
     */
    public void append(String messageId) {
        this.referencesChain = referencesChain == null || referencesChain.isBlank()
                ? messageId
                : referencesChain + " " + messageId;
        this.lastMessageId = messageId;
        this.messageCount = messageCount + 1;
    }
}
