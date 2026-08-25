package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListService;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListCopyRequest;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListCreateRequest;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListMemberCreateRequest;
import com.aleksandarparipovic.marel_app.mailing_list.dto.MailingListResponse;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipientService;
import com.aleksandarparipovic.marel_app.production_order_recipient.dto.ProductionOrderRecipientResponse;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing who a production order tells, at the moment it is written.
 *
 * <p>Three ways, and the difference between them is what happens to the list
 * afterwards: attach an existing one and it stays shared; COPY one and the
 * copier can then add and remove without touching a list other people rely on.
 * A global list edited because one order needed one extra address is how a
 * shared list stops being trustworthy.
 *
 * <p>What must stay true, and is asserted below:
 * <ul>
 *   <li>lists chosen on the create form are attached in the SAME transaction, so
 *       an order never exists having "used" a list whose members were not copied;
 *   <li>a copy is the copier's own PRIVATE list whatever the source was, and
 *       editing it leaves the source alone;
 *   <li>copying twice does not fail on the per-owner name rule;
 *   <li>attaching through order creation is not a way around
 *       PRODUCTION_ORDER_RECIPIENT_MANAGE.
 * </ul>
 */
@Transactional
class OrderMailingListChoiceIT extends AbstractIntegrationTest {

    @Autowired private MailingListService mailingListService;
    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private ProductionOrderRecipientService recipientService;
    @Autowired private UserRepository userRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User anActor() {
        return userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == UserAccountStatus.ACTIVE)
                .findFirst().orElseThrow();
    }

    /**
     * Signs in as the given user holding the named ROLES, the way
     * JwtAuthenticationFilter does.
     *
     * <p>Roles, not permission names: PermissionService reads ROLE_ authorities
     * and maps them through RolePermissions. Granting a permission string
     * directly would authorise nothing and would test the wrong thing.
     */
    private void signIn(User user, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        /*
         * The principal is CustomUserDetails, not the username: CurrentUserService
         * reads the id off the principal object and answers null for anything
         * else — which would make the caller nobody, and every ownership check
         * fail for reasons that have nothing to do with what is being tested.
         */
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomUserDetails(user), null, authorities));
    }

    private Long aListWith(Long ownerId, String... emails) {
        MailingListCreateRequest create = new MailingListCreateRequest();
        create.setName("Lista-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
        Long listId = mailingListService.create(create, ownerId).id();

        for (String email : emails) {
            MailingListMemberCreateRequest member = new MailingListMemberCreateRequest();
            member.setExternalEmail(email);
            mailingListService.addMember(listId, member, ownerId);
        }
        return listId;
    }

    private ProductionOrderCreateRequest anOrderWith(List<Long> mailingListIds) {
        int n = COUNTER.incrementAndGet();
        return new ProductionOrderCreateRequest(
                "NAL-" + n + "-" + System.nanoTime(), "Nalog " + n, null,
                null, false, null, null, null, false, false, false,
                List.of(), List.of(), mailingListIds);
    }

    private static List<String> emailsOf(List<ProductionOrderRecipientResponse> recipients) {
        return recipients.stream()
                .map(ProductionOrderRecipientResponse::recipientEmail)
                .sorted().toList();
    }

    // ── Chosen while the order is being written ─────────────────────────────

    @Test
    @DisplayName("lists picked on the create form become the order's recipients")
    void listsChosenAtCreationAreSnapshotted() {
        User actor = anActor();
        signIn(actor, "commercial");
        Long listId = aListWith(actor.getId(), "a@firma.rs", "b@firma.rs");

        ProductionOrderDetailDto order = productionOrderService.create(anOrderWith(List.of(listId)));

        assertThat(emailsOf(recipientService.listRecipients(order.id())))
                .containsExactly("a@firma.rs", "b@firma.rs");
    }

    /*
     * Two lists sharing an address is the ordinary case — a manager on both the
     * production list and the sales one. They get ONE email.
     */
    @Test
    @DisplayName("somebody on two chosen lists is still told once")
    void overlappingListsDeduplicate() {
        User actor = anActor();
        signIn(actor, "commercial");
        Long first = aListWith(actor.getId(), "sef@firma.rs", "a@firma.rs");
        Long second = aListWith(actor.getId(), "sef@firma.rs", "b@firma.rs");

        ProductionOrderDetailDto order =
                productionOrderService.create(anOrderWith(List.of(first, second)));

        assertThat(emailsOf(recipientService.listRecipients(order.id())))
                .containsExactly("a@firma.rs", "b@firma.rs", "sef@firma.rs");
    }

    /* A double click on the same list must not cost somebody their whole order. */
    @Test
    @DisplayName("the same list picked twice is not an error")
    void repeatedPickIsTolerated() {
        User actor = anActor();
        signIn(actor, "commercial");
        Long listId = aListWith(actor.getId(), "a@firma.rs");

        ProductionOrderDetailDto order =
                productionOrderService.create(anOrderWith(List.of(listId, listId)));

        assertThat(emailsOf(recipientService.listRecipients(order.id())))
                .containsExactly("a@firma.rs");
    }

    @Test
    @DisplayName("an order for nobody in particular still creates")
    void noListsIsFine() {
        User actor = anActor();
        signIn(actor, "commercial");

        ProductionOrderDetailDto order = productionOrderService.create(anOrderWith(null));

        assertThat(recipientService.listRecipients(order.id())).isEmpty();
    }

    /*
     * Attaching is gated on the recipients controller, and this path does not go
     * through it. Today every role that may create an order also holds the
     * permission, so this never fires in practice — which is why it is asserted:
     * creating an order must not become the way around it.
     */
    @Test
    @DisplayName("creating an order is not a way around the recipient permission")
    void creationIsNotABypass() {
        User actor = anActor();
        /*
         * Signed in, but holding no role that grants anything — which is the
         * shape of the risk: a role that may create orders and nothing else.
         */
        signIn(actor);
        Long listId = aListWith(actor.getId(), "a@firma.rs");

        assertThatThrownBy(() -> productionOrderService.create(anOrderWith(List.of(listId))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ── Copying ────────────────────────────────────────────────────────────

    /*
     * THE ONE WITH TEETH.
     *
     * The whole reason to copy instead of attach: what the copier does next must
     * not reach the people relying on the original.
     */
    @Test
    @DisplayName("editing a copy leaves the list it came from alone")
    void copyIsIndependentOfItsSource() {
        User actor = anActor();
        Long sourceId = aListWith(actor.getId(), "a@firma.rs", "b@firma.rs");

        MailingListResponse copy = mailingListService.copy(sourceId, null, actor.getId());

        MailingListMemberCreateRequest extra = new MailingListMemberCreateRequest();
        extra.setExternalEmail("novi@firma.rs");
        mailingListService.addMember(copy.id(), extra, actor.getId());

        assertThat(mailingListService.listMembers(copy.id(), actor.getId()))
                .hasSize(3);
        assertThat(mailingListService.listMembers(sourceId, actor.getId()))
                .as("the source must not have gained the copier's addition")
                .hasSize(2);
    }

    @Test
    @DisplayName("a copy starts with the source's people")
    void copyCarriesTheMembers() {
        User actor = anActor();
        Long sourceId = aListWith(actor.getId(), "a@firma.rs", "b@firma.rs");

        MailingListResponse copy = mailingListService.copy(sourceId, null, actor.getId());

        assertThat(mailingListService.listMembers(copy.id(), actor.getId()))
                .extracting(m -> m.effectiveEmail())
                .containsExactlyInAnyOrder("a@firma.rs", "b@firma.rs");
    }

    /*
     * Whatever the source was. A copy of a global list landing back in the shared
     * pool would put one person's private edit in front of everybody.
     */
    @Test
    @DisplayName("a copy is the copier's own private list, even from a global one")
    void copyOfGlobalIsPrivate() {
        User actor = anActor();
        signIn(actor, "commercial");

        MailingListCreateRequest create = new MailingListCreateRequest();
        create.setName("Zajednicka-" + System.nanoTime());
        create.setVisibility(MailingListVisibility.GLOBAL);
        Long globalId = mailingListService.create(create, actor.getId()).id();

        MailingListResponse copy = mailingListService.copy(globalId, null, actor.getId());

        assertThat(copy.visibility()).isEqualTo(MailingListVisibility.PRIVATE);
        assertThat(copy.ownerUserId()).isEqualTo(actor.getId());
    }

    /*
     * Names are unique per owner among active lists. Copying the same list twice
     * is an ordinary thing to do, and refusing it would read as the feature being
     * broken when the person did nothing wrong.
     */
    @Test
    @DisplayName("copying the same list twice does not collide on the name")
    void copyingTwiceWorks() {
        User actor = anActor();
        Long sourceId = aListWith(actor.getId(), "a@firma.rs");

        MailingListResponse first = mailingListService.copy(sourceId, null, actor.getId());
        MailingListResponse second = mailingListService.copy(sourceId, null, actor.getId());

        assertThat(first.name()).isNotEqualTo(second.name());
    }

    /* A name the caller typed is theirs to correct, so it is refused, not adjusted. */
    @Test
    @DisplayName("a chosen name that is already taken is refused rather than renamed")
    void chosenNameCollisionIsRefused() {
        User actor = anActor();
        Long sourceId = aListWith(actor.getId(), "a@firma.rs");
        String taken = "Zauzeto-" + System.nanoTime();

        MailingListCopyRequest request = new MailingListCopyRequest();
        request.setName(taken);
        mailingListService.copy(sourceId, request, actor.getId());

        MailingListCopyRequest again = new MailingListCopyRequest();
        again.setName(taken);
        assertThatThrownBy(() -> mailingListService.copy(sourceId, again, actor.getId()))
                .isInstanceOf(ConflictException.class);
    }
}
