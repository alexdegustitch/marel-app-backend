package com.aleksandarparipovic.marel_app.sample_order_recipient;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListService;
import com.aleksandarparipovic.marel_app.mailing_list_member.MailingListMember;
import com.aleksandarparipovic.marel_app.production_order_recipient.RecipientSourceType;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderStatus;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.sample_order_mailing_list.SampleOrderMailingList;
import com.aleksandarparipovic.marel_app.sample_order_mailing_list.SampleOrderMailingListRepository;
import com.aleksandarparipovic.marel_app.sample_order_recipient.dto.SampleOrderRecipientResponse;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sample-order mailing lists and the recipient snapshot.
 *
 * <p>The rule this service exists to enforce: <b>attaching a mailing list copies
 * its members; it does not subscribe to them.</b> After the copy, the order's
 * recipients are frozen against later edits of that list. Email is always sent
 * from the snapshot.
 *
 * <p>The production-order service with one word changed throughout, and
 * deliberately not merged with it. The two differ in the one place that matters
 * — what "this order is finished" means, and therefore when the snapshot stops
 * moving — and a shared service would have had to branch on the order's kind in
 * every method to say it.
 */
@Service
@RequiredArgsConstructor
public class SampleOrderRecipientService {

    private final SampleOrderRepository sampleOrderRepository;
    private final SampleOrderMailingListRepository orderMailingListRepository;
    private final SampleOrderRecipientRepository recipientRepository;
    private final MailingListService mailingListService;
    private final UserRepository userRepository;

    /**
     * Attaches a mailing list and snapshots its active members, atomically.
     *
     * <p>Both writes are in this transaction: if snapshotting throws, the link is
     * rolled back too, so an order can never end up "using" a list whose members
     * were never copied.
     */
    @Transactional
    public List<SampleOrderRecipientResponse> attachMailingList(
            Long orderId, Long mailingListId, Long actorId
    ) {
        SampleOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        MailingList list = mailingListService.loadOrThrow(mailingListId);
        // Attaching somebody else's private list must not be possible just because
        // the caller may edit this sample order.
        mailingListService.requireCanRead(list, actorId);

        if (list.isArchived()) {
            throw new ConflictException(
                    "Arhivirana lista ne može da se doda na nalog za izradu uzoraka.");
        }

        if (orderMailingListRepository.existsBySampleOrder_IdAndMailingList_Id(orderId, mailingListId)) {
            throw new ConflictException("Ova lista je već dodata na nalog.");
        }

        User actor = loadUser(actorId);

        orderMailingListRepository.save(SampleOrderMailingList.builder()
                .sampleOrder(order)
                .mailingList(list)
                .addedBy(actor)
                .build());

        for (MailingListMember member : mailingListService.activeDeliverableMembers(mailingListId)) {
            // Deduplicated by normalized address: a person on three selected lists,
            // or on a list AND entered manually, still gets exactly one row.
            addRecipientIfAbsent(
                    order,
                    member.effectiveEmail(),
                    member.effectiveName(),
                    member.getUser(),
                    RecipientSourceType.MAILING_LIST,
                    list,
                    actor
            );
        }

        return listRecipients(orderId);
    }

    /**
     * Detaches a mailing list.
     *
     * <p>While the order is still open, this also archives the active recipients
     * whose ONLY source was this list. MANUAL and SYSTEM recipients are never
     * touched, and neither is a row attributed to a different list. Once the
     * order is closed nothing may be detached at all.
     */
    @Transactional
    public List<SampleOrderRecipientResponse> detachMailingList(
            Long orderId, Long mailingListId, Long actorId
    ) {
        SampleOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        SampleOrderMailingList link = orderMailingListRepository
                .findBySampleOrder_IdAndMailingList_Id(orderId, mailingListId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lista nije dodata na ovaj nalog: " + mailingListId));

        User actor = loadUser(actorId);

        recipientRepository
                .findBySampleOrder_IdAndSourceMailingList_IdAndRemovedAtIsNull(orderId, mailingListId)
                .forEach(recipient -> recipient.remove(actor));

        orderMailingListRepository.delete(link);

        return listRecipients(orderId);
    }

    /** Adds one address typed in for this order only. */
    @Transactional
    public SampleOrderRecipientResponse addManualRecipient(
            Long orderId, String email, String name, Long actorId
    ) {
        SampleOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        String normalized = normalizeEmail(email);
        requireValidEmail(normalized);

        User actor = loadUser(actorId);

        // If the address is already an active recipient, adding it again is a no-op
        // rather than an error — the goal is "this person is on the order", which is
        // already true.
        SampleOrderRecipient recipient = addRecipientIfAbsent(
                order, normalized, name, resolveUserByEmail(normalized),
                RecipientSourceType.MANUAL, null, actor);

        return toResponse(recipient);
    }

    /** Adds a backend-determined recipient. No human author, so added_by stays NULL. */
    @Transactional
    public Optional<SampleOrderRecipient> addSystemRecipient(
            SampleOrder order, String email, String name, User user
    ) {
        String normalized = normalizeEmail(email);
        if (normalized == null || !isValidEmail(normalized)) {
            return Optional.empty();
        }
        return Optional.of(addRecipientIfAbsent(
                order, normalized, name, user, RecipientSourceType.SYSTEM, null, null));
    }

    @Transactional
    public void removeRecipient(Long orderId, Long recipientId, Long actorId) {
        SampleOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        SampleOrderRecipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Primalac nije pronađen: " + recipientId));

        if (!recipient.getSampleOrder().getId().equals(orderId)) {
            throw new IllegalArgumentException("Primalac ne pripada ovom nalogu.");
        }

        recipient.remove(loadUser(actorId));
    }

    @Transactional(readOnly = true)
    public List<SampleOrderRecipientResponse> listRecipients(Long orderId) {
        return recipientRepository.findActiveBySampleOrderId(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The addresses an order actually mails. Reads the snapshot — never mailing-list
     * membership — so a list edited after the order was prepared cannot change who
     * gets told about it.
     */
    @Transactional(readOnly = true)
    public List<String> resolveEffectiveEmails(Long orderId) {
        return recipientRepository.findActiveBySampleOrderId(orderId).stream()
                .map(SampleOrderRecipient::getRecipientEmail)
                .distinct()
                .toList();
    }

    // ---------- internals ----------

    /**
     * Inserts unless this address is already an active recipient of the order.
     *
     * <p>The in-application check keeps the common case clean; the partial unique
     * index uq_sor_order_email_active is what actually guarantees it under
     * concurrency, since two simultaneous attaches could both pass this check.
     */
    private SampleOrderRecipient addRecipientIfAbsent(
            SampleOrder order,
            String email,
            String name,
            User user,
            RecipientSourceType sourceType,
            MailingList sourceList,
            User actor
    ) {
        return recipientRepository
                .findBySampleOrder_IdAndRecipientEmailAndRemovedAtIsNull(order.getId(), email)
                .orElseGet(() -> recipientRepository.save(
                        SampleOrderRecipient.builder()
                                .sampleOrder(order)
                                .user(user)
                                .recipientEmail(email)
                                .recipientName(normalize(name))
                                .sourceType(sourceType)
                                .sourceMailingList(sourceList)
                                .addedBy(sourceType == RecipientSourceType.SYSTEM ? null : actor)
                                .build()));
    }

    /**
     * Closed is terminal: the order has been communicated, so its recipient
     * history must stop moving.
     */
    private static void requireSnapshotEditable(SampleOrder order) {
        if (SampleOrderStatus.isClosed(order.getStatus())) {
            throw new ConflictException(
                    "Nalog je zatvoren — lista primalaca više ne može da se menja.");
        }
    }

    private User resolveUserByEmail(String email) {
        return userRepository.findByEmailAddressIgnoreCase(email).orElse(null);
    }

    private SampleOrder loadOrder(Long orderId) {
        return sampleOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Nalog za izradu uzoraka nije pronađen: " + orderId));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static void requireValidEmail(String email) {
        if (email == null || !isValidEmail(email)) {
            throw new IllegalArgumentException("Email adresa nije validna.");
        }
    }

    /**
     * Mirrors the database check. The whitespace ban is what blocks CR/LF SMTP
     * header injection, so it is enforced before the value ever reaches a mailer.
     */
    private static boolean isValidEmail(String email) {
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private SampleOrderRecipientResponse toResponse(SampleOrderRecipient r) {
        return new SampleOrderRecipientResponse(
                r.getId(),
                r.getSampleOrder().getId(),
                r.getUser() == null ? null : r.getUser().getId(),
                r.getRecipientEmail(),
                r.getRecipientName(),
                r.getSourceType(),
                r.getSourceMailingList() == null ? null : r.getSourceMailingList().getId(),
                r.getSourceMailingList() == null ? null : r.getSourceMailingList().getName(),
                r.getCreatedAt()
        );
    }
}
