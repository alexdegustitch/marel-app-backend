package com.aleksandarparipovic.marel_app.production_order_recipient;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.mailing_list.MailingList;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListService;
import com.aleksandarparipovic.marel_app.mailing_list_member.MailingListMember;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_mailing_list.ProductionOrderMailingList;
import com.aleksandarparipovic.marel_app.production_order_mailing_list.ProductionOrderMailingListRepository;
import com.aleksandarparipovic.marel_app.production_order_recipient.dto.ProductionOrderRecipientResponse;
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
 * Production-order mailing lists and the recipient snapshot.
 *
 * <p>The rule this service exists to enforce: <b>attaching a mailing list copies
 * its members; it does not subscribe to them.</b> After the copy, the order's
 * recipients are frozen against later edits of that list. Email is always sent
 * from the snapshot.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderRecipientService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderMailingListRepository orderMailingListRepository;
    private final ProductionOrderRecipientRepository recipientRepository;
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
    public List<ProductionOrderRecipientResponse> attachMailingList(
            Long orderId, Long mailingListId, Long actorId
    ) {
        ProductionOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        MailingList list = mailingListService.loadOrThrow(mailingListId);
        // Attaching somebody else's private list must not be possible just because
        // the caller may edit this production order.
        mailingListService.requireCanRead(list, actorId);

        if (list.isArchived()) {
            throw new ConflictException(
                    "Arhivirana lista ne može da se doda na nalog za proizvodnju.");
        }

        if (orderMailingListRepository.existsByProductionOrder_IdAndMailingList_Id(
                orderId, mailingListId)) {
            throw new ConflictException("Ova lista je već dodata na nalog.");
        }

        User actor = loadUser(actorId);

        orderMailingListRepository.save(ProductionOrderMailingList.builder()
                .productionOrder(order)
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
     * <p>The chosen rule (see the business-rules document, §5.2): while the order
     * is CREATED, this also archives the active recipients whose ONLY source was
     * this list. MANUAL and SYSTEM recipients are never touched, and neither is a
     * row attributed to a different list. Once the order is DELIVERED nothing may
     * be detached at all.
     */
    @Transactional
    public List<ProductionOrderRecipientResponse> detachMailingList(
            Long orderId, Long mailingListId, Long actorId
    ) {
        ProductionOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        ProductionOrderMailingList link = orderMailingListRepository
                .findByProductionOrder_IdAndMailingList_Id(orderId, mailingListId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lista nije dodata na ovaj nalog: " + mailingListId));

        User actor = loadUser(actorId);

        recipientRepository
                .findByProductionOrder_IdAndSourceMailingList_IdAndRemovedAtIsNull(
                        orderId, mailingListId)
                .forEach(recipient -> recipient.remove(actor));

        orderMailingListRepository.delete(link);

        return listRecipients(orderId);
    }

    /** Adds one address typed in for this order only. */
    @Transactional
    public ProductionOrderRecipientResponse addManualRecipient(
            Long orderId, String email, String name, Long actorId
    ) {
        ProductionOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        String normalized = normalizeEmail(email);
        requireValidEmail(normalized);

        User actor = loadUser(actorId);

        // If the address is already an active recipient, adding it again is a no-op
        // rather than an error — the goal is "this person is on the order", which is
        // already true.
        ProductionOrderRecipient recipient = addRecipientIfAbsent(
                order, normalized, name, resolveUserByEmail(normalized),
                RecipientSourceType.MANUAL, null, actor);

        return toResponse(recipient);
    }

    /** Adds a backend-determined recipient. No human author, so added_by stays NULL. */
    @Transactional
    public Optional<ProductionOrderRecipient> addSystemRecipient(
            ProductionOrder order, String email, String name, User user
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
        ProductionOrder order = loadOrder(orderId);
        requireSnapshotEditable(order);

        ProductionOrderRecipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Primalac nije pronađen: " + recipientId));

        if (!recipient.getProductionOrder().getId().equals(orderId)) {
            throw new IllegalArgumentException("Primalac ne pripada ovom nalogu.");
        }

        recipient.remove(loadUser(actorId));
    }

    @Transactional(readOnly = true)
    public List<ProductionOrderRecipientResponse> listRecipients(Long orderId) {
        return recipientRepository.findActiveByProductionOrderId(orderId).stream()
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
        return recipientRepository.findActiveByProductionOrderId(orderId).stream()
                .map(ProductionOrderRecipient::getRecipientEmail)
                .distinct()
                .toList();
    }

    // ---------- internals ----------

    /**
     * Inserts unless this address is already an active recipient of the order.
     *
     * <p>The in-application check keeps the common case clean; the partial unique
     * index uq_po_recipients_order_email_active is what actually guarantees it
     * under concurrency, since two simultaneous attaches could both pass this check.
     */
    private ProductionOrderRecipient addRecipientIfAbsent(
            ProductionOrder order,
            String email,
            String name,
            User user,
            RecipientSourceType sourceType,
            MailingList sourceList,
            User actor
    ) {
        return recipientRepository
                .findByProductionOrder_IdAndRecipientEmailAndRemovedAtIsNull(order.getId(), email)
                .orElseGet(() -> recipientRepository.save(
                        ProductionOrderRecipient.builder()
                                .productionOrder(order)
                                .user(user)
                                .recipientEmail(email)
                                .recipientName(normalize(name))
                                .sourceType(sourceType)
                                .sourceMailingList(sourceList)
                                .addedBy(sourceType == RecipientSourceType.SYSTEM ? null : actor)
                                .build()));
    }

    /**
     * DELIVERED is terminal: the order has been communicated, so its recipient
     * history must stop moving.
     */
    private static void requireSnapshotEditable(ProductionOrder order) {
        if (order.getStatus() == ProductionOrderStatus.DELIVERED) {
            throw new ConflictException(
                    "Nalog je isporučen — lista primalaca više ne može da se menja.");
        }
    }

    private User resolveUserByEmail(String email) {
        return userRepository.findByEmailAddressIgnoreCase(email).orElse(null);
    }

    private ProductionOrder loadOrder(Long orderId) {
        return productionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Nalog za proizvodnju nije pronađen: " + orderId));
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

    private ProductionOrderRecipientResponse toResponse(ProductionOrderRecipient r) {
        return new ProductionOrderRecipientResponse(
                r.getId(),
                r.getProductionOrder().getId(),
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
