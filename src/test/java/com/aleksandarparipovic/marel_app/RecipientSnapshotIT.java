package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListService;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListCreateRequest;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListMemberCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipientService;
import com.aleksandarparipovic.marel_app.production_order_recipient.dto.ProductionOrderRecipientResponse;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single most important rule in this feature set: a production order's
 * recipients are a SNAPSHOT, not a live view of a mailing list.
 */
@Transactional
class RecipientSnapshotIT extends AbstractIntegrationTest {

    @Autowired private MailingListService mailingListService;
    @Autowired private ProductionOrderRecipientService recipientService;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private UserRepository userRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Long actorId() {
        return userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == UserAccountStatus.ACTIVE)
                .map(User::getId)
                .findFirst().orElseThrow();
    }

    private ProductionOrder anOrder() {
        int n = COUNTER.incrementAndGet();
        ProductionOrder order = new ProductionOrder();
        order.setCode("TEST-" + n + "-" + System.nanoTime());
        order.setName("Test order " + n);
        order.setStatus(ProductionOrderStatus.CREATED);
        order.setTestingRequired(false);
        order.setIsActive(true);
        return productionOrderRepository.save(order);
    }

    private Long aListWith(String... emails) {
        MailingListCreateRequest create = new MailingListCreateRequest();
        create.setName("List-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
        Long listId = mailingListService.create(create, actorId()).id();

        for (String email : emails) {
            MailingListMemberCreateRequest member = new MailingListMemberCreateRequest();
            member.setExternalEmail(email);
            mailingListService.addMember(listId, member, actorId());
        }
        return listId;
    }

    private static List<String> emailsOf(List<ProductionOrderRecipientResponse> recipients) {
        return recipients.stream().map(ProductionOrderRecipientResponse::recipientEmail).sorted().toList();
    }

    @Test
    @DisplayName("attaching a mailing list copies its members into the order")
    void attachCreatesSnapshot() {
        ProductionOrder order = anOrder();
        Long listId = aListWith("a@firma.rs", "b@firma.rs");

        var recipients = recipientService.attachMailingList(order.getId(), listId, actorId());

        assertThat(emailsOf(recipients)).containsExactly("a@firma.rs", "b@firma.rs");
    }

    @Test
    @DisplayName("editing the mailing list afterwards does NOT change the order's recipients")
    void snapshotIsIndependentOfLaterListEdits() {
        ProductionOrder order = anOrder();
        Long listId = aListWith("a@firma.rs", "b@firma.rs");
        recipientService.attachMailingList(order.getId(), listId, actorId());

        // Change the list in both directions after the snapshot was taken.
        MailingListMemberCreateRequest added = new MailingListMemberCreateRequest();
        added.setExternalEmail("c@firma.rs");
        mailingListService.addMember(listId, added, actorId());

        Long memberToRemove = mailingListService.listMembers(listId, actorId()).stream()
                .filter(m -> "a@firma.rs".equals(m.effectiveEmail()))
                .findFirst().orElseThrow().id();
        mailingListService.removeMember(listId, memberToRemove, actorId());

        // The order still mails exactly who it did at attach time.
        assertThat(emailsOf(recipientService.listRecipients(order.getId())))
                .containsExactly("a@firma.rs", "b@firma.rs");
    }

    @Test
    @DisplayName("an address in two lists produces exactly one recipient")
    void deduplicatesAcrossLists() {
        ProductionOrder order = anOrder();
        Long first = aListWith("shared@firma.rs", "only-first@firma.rs");
        Long second = aListWith("shared@firma.rs", "only-second@firma.rs");

        recipientService.attachMailingList(order.getId(), first, actorId());
        var recipients = recipientService.attachMailingList(order.getId(), second, actorId());

        assertThat(emailsOf(recipients))
                .containsExactly("only-first@firma.rs", "only-second@firma.rs", "shared@firma.rs");
        // One equivalent email, not two.
        assertThat(recipients).hasSize(3);
    }

    @Test
    @DisplayName("a manual address that duplicates an existing recipient does not add a second row")
    void deduplicatesManualAgainstList() {
        ProductionOrder order = anOrder();
        Long listId = aListWith("shared@firma.rs");
        recipientService.attachMailingList(order.getId(), listId, actorId());

        // Different case on purpose — deduplication is case-insensitive.
        recipientService.addManualRecipient(order.getId(), "SHARED@FIRMA.RS", "Dup", actorId());

        assertThat(recipientService.listRecipients(order.getId())).hasSize(1);
    }

    @Test
    @DisplayName("detaching a list removes only the recipients it alone contributed")
    void detachRemovesOnlyItsOwnRecipients() {
        ProductionOrder order = anOrder();
        Long first = aListWith("shared@firma.rs", "only-first@firma.rs");
        Long second = aListWith("shared@firma.rs", "only-second@firma.rs");

        recipientService.attachMailingList(order.getId(), first, actorId());
        recipientService.attachMailingList(order.getId(), second, actorId());

        var remaining = recipientService.detachMailingList(order.getId(), second, actorId());

        // shared@ stays: it is attributed to the first list, which is still attached.
        assertThat(emailsOf(remaining)).containsExactly("only-first@firma.rs", "shared@firma.rs");
    }

    @Test
    @DisplayName("a DELIVERED order's recipient snapshot is locked")
    void deliveredOrderIsLocked() {
        ProductionOrder order = anOrder();
        Long listId = aListWith("a@firma.rs");
        recipientService.attachMailingList(order.getId(), listId, actorId());

        order.setStatus(ProductionOrderStatus.DELIVERED);
        productionOrderRepository.save(order);

        assertThatThrownBy(() ->
                recipientService.addManualRecipient(order.getId(), "late@firma.rs", null, actorId()))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() ->
                recipientService.detachMailingList(order.getId(), listId, actorId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("email is resolved from the snapshot, never from live membership")
    void effectiveEmailsComeFromSnapshot() {
        ProductionOrder order = anOrder();
        Long listId = aListWith("a@firma.rs");
        recipientService.attachMailingList(order.getId(), listId, actorId());

        MailingListMemberCreateRequest added = new MailingListMemberCreateRequest();
        added.setExternalEmail("added-later@firma.rs");
        mailingListService.addMember(listId, added, actorId());

        assertThat(recipientService.resolveEffectiveEmails(order.getId()))
                .containsExactly("a@firma.rs");
    }
}
