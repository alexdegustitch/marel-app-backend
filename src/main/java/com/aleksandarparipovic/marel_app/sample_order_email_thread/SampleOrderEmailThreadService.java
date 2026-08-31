package com.aleksandarparipovic.marel_app.sample_order_email_thread;

import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out the threading headers for the next mail about a sample order.
 *
 * <p>This is the only place that assigns a Message-ID for these orders, so the
 * chain can never disagree with what was actually sent.
 */
@Service
public class SampleOrderEmailThreadService {

    private static final int SUBJECT_MAX = 255;

    /**
     * Message-IDs must sit on a domain we control — receivers treat an id from
     * an unrelated domain as a weak spam signal. Taken from the sender address
     * so there is one answer to "which domain is this installation", rather than
     * a second setting that can drift away from the first.
     */
    private final String idDomain;

    private final SampleOrderEmailThreadRepository repository;

    public SampleOrderEmailThreadService(
            SampleOrderEmailThreadRepository repository,
            @Value("${app.mail.from}") String fromAddress
    ) {
        this.repository = repository;
        int at = fromAddress.lastIndexOf('@');
        this.idDomain = at >= 0 ? fromAddress.substring(at + 1) : fromAddress;
    }

    /**
     * The headers that put this mail in the order's conversation.
     *
     * @param subject     what the recipient reads. The FIRST message decides it
     *                    for good; later ones get it back with "Re: " in front.
     * @param inReplyTo   null for the first message in the conversation
     * @param references  empty for the first message
     */
    public record ThreadHeaders(
            String subject,
            String messageId,
            String inReplyTo,
            String references
    ) {
    }

    /**
     * Opens the conversation if this is the first mail, continues it otherwise,
     * and records the new id as the newest message.
     *
     * <p>MANDATORY on purpose: the caller is queueing a delivery row that will
     * carry this id, and the two writes must stand or fall together. An id
     * appended here but never queued would leave the chain pointing at a message
     * nobody ever received, and every later mail would reply to a ghost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ThreadHeaders nextMessage(SampleOrder order) {
        SampleOrderEmailThread thread = repository
                .findBySampleOrder_Id(order.getId())
                .orElse(null);

        if (thread == null) {
            String subject = subjectFor(order);
            String messageId = messageId(order.getId(), 1);

            thread = repository.save(SampleOrderEmailThread.builder()
                    .sampleOrder(order)
                    .subjectBase(subject)
                    .rootMessageId(messageId)
                    .lastMessageId(messageId)
                    .referencesChain(messageId)
                    .messageCount(1)
                    .build());

            // No In-Reply-To and no References: there is nothing yet to reply to.
            return new ThreadHeaders(subject, messageId, null, null);
        }

        String parent = thread.getLastMessageId();
        String references = thread.getReferencesChain();
        String messageId = messageId(order.getId(), thread.getMessageCount() + 1);

        thread.append(messageId);
        repository.save(thread);

        return new ThreadHeaders(
                "Re: " + thread.getSubjectBase(), messageId, parent, references);
    }

    /**
     * Frozen at the first message. Says "Uzorci" rather than "Nalog" so that a
     * sample run and a production order for the same customer are not two
     * threads a person has to open to tell apart.
     */
    private String subjectFor(SampleOrder order) {
        String subject = "Uzorci " + order.getCode()
                + (order.getName() == null || order.getName().isBlank()
                        ? "" : " — " + order.getName());

        return subject.length() <= SUBJECT_MAX
                ? subject
                : subject.substring(0, SUBJECT_MAX - 1) + "…";
    }

    /**
     * The counter, not a timestamp: two changes saved in the same second must
     * still get different ids, or the second mail is discarded as a duplicate of
     * the first by every client that dedupes on Message-ID.
     *
     * <p>The "so-" prefix keeps these ids apart from the production order's
     * "po-" ones — order 7 exists in both tables, and a shared id would put two
     * unrelated conversations in one thread.
     */
    private String messageId(Long orderId, int sequence) {
        return "<so-" + orderId + "-" + sequence + "@" + idDomain + ">";
    }
}
