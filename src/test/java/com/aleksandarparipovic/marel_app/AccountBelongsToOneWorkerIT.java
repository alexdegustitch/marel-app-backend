package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user.UserController;
import com.aleksandarparipovic.marel_app.user.UserService;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which worker a sign-in account belongs to.
 *
 * <p>Until {@code V9} the two were unrelated tables that happened to hold names,
 * so "show me my payslips" was not a question the data could answer. The link is
 * stated by an administrator and never inferred: matching on a name or an
 * e-mail address would occasionally succeed on the wrong row, and the cost of
 * that mistake is one person reading another person's pay.
 *
 * <p>The rule with teeth is ONE ACCOUNT PER WORKER. It is asserted twice on
 * purpose — once as the sentence the administrator is answered with, and once as
 * the unique index that holds whether or not anybody goes through the service.
 */
@Transactional
class AccountBelongsToOneWorkerIT extends AbstractIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;
    /*
     * The CONTROLLER, not the service: @PreAuthorize lives on the endpoint and
     * is applied by the proxy. Calling the service straight through would test
     * the rule with the guard removed, which is the one arrangement that must
     * not pass.
     */
    @Autowired private UserController userController;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private UserDto anAccount() {
        int n = COUNTER.incrementAndGet();
        String role = roleRepository.findAll().getFirst().getRoleName();
        return userService.create(
                "nalog" + n, "Test1234", "nalog" + n + "@example.rs",
                "Test", "Nalog" + n, null, role);
    }

    private Employee aWorker() {
        return fixture.scenario().build().employee();
    }

    private UserUpdateRequest linkTo(Long employeeId) {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setEmployeeId(employeeId);
        return request;
    }

    @Test
    @DisplayName("an administrator says which worker an account is")
    void linkingNamesTheWorker() {
        UserDto account = anAccount();
        Employee worker = aWorker();

        // Not linked is the state every account starts in, including this one.
        assertThat(account.getEmployeeId()).isNull();

        UserDto linked = userService.update(account.getId(), linkTo(worker.getId()));

        assertThat(linked.getEmployeeId()).isEqualTo(worker.getId());
        assertThat(linked.getEmployeeName()).isEqualTo(worker.getFullName());
    }

    @Test
    @DisplayName("a worker who already has an account cannot be given a second one")
    void oneAccountPerWorker() {
        Employee worker = aWorker();
        UserDto first = anAccount();
        UserDto second = anAccount();

        userService.update(first.getId(), linkTo(worker.getId()));

        assertThatThrownBy(() -> userService.update(second.getId(), linkTo(worker.getId())))
                .isInstanceOf(ConflictException.class)
                // The person doing it is told WHOSE account is in the way, or
                // they are left guessing at a refusal they cannot act on.
                .hasMessageContaining(worker.getFullName())
                .hasMessageContaining("nalog" );
    }

    @Test
    @DisplayName("re-stating the link an account already has is not a conflict")
    void relinkingTheSameWorkerIsAllowed() {
        Employee worker = aWorker();
        UserDto account = anAccount();

        userService.update(account.getId(), linkTo(worker.getId()));

        // A screen that saves the whole form sends this field again unchanged.
        // Treating that as "somebody else already has this worker" would refuse
        // every second save.
        assertThat(userService.update(account.getId(), linkTo(worker.getId())).getEmployeeId())
                .isEqualTo(worker.getId());
    }

    @Test
    @DisplayName("cutting an account loose has to be said, not left out")
    void unlinkingIsExplicit() {
        Employee worker = aWorker();
        UserDto account = anAccount();
        userService.update(account.getId(), linkTo(worker.getId()));

        // An update about something else leaves the link exactly where it was —
        // null means "leave it", as it does for every other field here.
        UserUpdateRequest renameOnly = new UserUpdateRequest();
        renameOnly.setFirstName("Novo");
        assertThat(userService.update(account.getId(), renameOnly).getEmployeeId())
                .isEqualTo(worker.getId());

        UserUpdateRequest unlink = new UserUpdateRequest();
        unlink.setUnlinkEmployee(true);
        UserDto cutLoose = userService.update(account.getId(), unlink);

        assertThat(cutLoose.getEmployeeId()).isNull();
        assertThat(cutLoose.getEmployeeName()).isNull();
    }

    @Test
    @DisplayName("set-it and clear-it in one request has no single intention to carry out")
    void bothAtOnceIsRefused() {
        Employee worker = aWorker();
        UserDto account = anAccount();

        UserUpdateRequest contradictory = linkTo(worker.getId());
        contradictory.setUnlinkEmployee(true);

        assertThatThrownBy(() -> userService.update(account.getId(), contradictory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a worker who does not exist is refused by name, not by constraint")
    void unknownWorkerIsRefused() {
        UserDto account = anAccount();

        assertThatThrownBy(() -> userService.update(account.getId(), linkTo(999_999_999L)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * The same rule again, this time with the service bypassed entirely.
     *
     * <p>A check in Java is a courtesy to whoever is typing; the index is the
     * guarantee. Without it a second writer — a script, a future endpoint, a
     * concurrent request that passed the check a millisecond earlier — could
     * leave two accounts pointing at one worker, and "whose payslip is this"
     * would have two answers.
     */
    @Test
    @DisplayName("the database refuses the second link too, whoever writes it")
    void theIndexIsTheGuarantee() {
        Employee worker = aWorker();
        User first = userRepository.findById(anAccount().getId()).orElseThrow();
        User second = userRepository.findById(anAccount().getId()).orElseThrow();

        first.setEmployee(worker);
        userRepository.saveAndFlush(first);

        second.setEmployee(worker);
        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String roleName) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + roleName))));
    }

    /*
     * WHO MAY SAY WHO SOMEBODY IS.
     *
     * The owner's rule: an administrator OR a supervisor. Supervisors know who on
     * the floor is who, so they are the right people to answer it. The danger is
     * how that gets implemented — widening `PATCH /api/users/{id}` to supervisors
     * would have handed them roles and passwords in the same breath, and a
     * supervisor who can set roleName can make themselves an administrator.
     *
     * So it is its own endpoint with its own capability, and these are the tests
     * that say so.
     */
    @Test
    @DisplayName("a supervisor may link an account to a worker")
    void supervisorMayLink() {
        Employee worker = aWorker();
        UserDto account = anAccount();

        signedInAs("supervisor");

        var linked = userController.linkEmployee(
                account.getId(), new UserController.EmployeeLinkRequest(worker.getId()));

        assertThat(linked.getBody()).isNotNull();
        assertThat(linked.getBody().getEmployeeId()).isEqualTo(worker.getId());
    }

    @Test
    @DisplayName("a supervisor may cut the link too")
    void supervisorMayUnlink() {
        Employee worker = aWorker();
        UserDto account = anAccount();
        userService.update(account.getId(), linkTo(worker.getId()));

        signedInAs("supervisor");

        assertThat(userController.unlinkEmployee(account.getId()).getBody().getEmployeeId()).isNull();
    }

    @Test
    @DisplayName("and still may not edit the account itself")
    void supervisorMayNotEditTheAccount() {
        UserDto account = anAccount();
        signedInAs("supervisor");

        // The whole reason the link is a separate endpoint. If this ever starts
        // passing, a supervisor can set roleName and make themselves an admin.
        UserUpdateRequest promoteSelf = new UserUpdateRequest();
        promoteSelf.setRoleName("admin");

        assertThatThrownBy(() -> userController.updateUser(account.getId(), promoteSelf))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("commercial staff may not link at all")
    void commercialMayNotLink() {
        Employee worker = aWorker();
        UserDto account = anAccount();

        signedInAs("commercial");

        assertThatThrownBy(() -> userController.linkEmployee(
                account.getId(), new UserController.EmployeeLinkRequest(worker.getId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    /*
     * The lookup the worker's page is built on: "whose account is this". Asked of
     * the database rather than answered by pulling every account into the browser
     * and searching there — which works on forty accounts and stops working
     * without saying so.
     */
    @Test
    @DisplayName("a worker's account can be found by the worker")
    void theAccountIsFindableFromTheWorkerSide() {
        Employee worker = aWorker();
        Employee somebodyElse = aWorker();
        UserDto account = anAccount();
        userService.update(account.getId(), linkTo(worker.getId()));

        var found = userService.getUsers(
                0, 10, null, null, null, worker.getId(), null,
                org.springframework.data.domain.Sort.Direction.ASC, "id");

        assertThat(found.getContent()).hasSize(1);
        assertThat(found.getContent().getFirst().getId()).isEqualTo(account.getId());

        // And a worker with no account answers with nothing, not with everybody.
        assertThat(userService.getUsers(
                0, 10, null, null, null, somebodyElse.getId(), null,
                org.springframework.data.domain.Sort.Direction.ASC, "id").getContent())
                .isEmpty();
    }

    /*
     * The directory searches one box over three columns. Username alone answered
     * almost nothing: most usernames here are generated, so the thing a reader
     * knows about a colleague was the one field it did not match.
     */
    @Test
    @DisplayName("the directory search matches a name, not only a username")
    void directorySearchesMoreThanTheUsername() {
        UserDto account = anAccount();

        UserUpdateRequest rename = new UserUpdateRequest();
        rename.setFirstName("Slobodanka");
        rename.setLastName("Mihajlović");
        userService.update(account.getId(), rename);

        var byName = userService.getUsers(
                0, 10, null, "mihajlov", null, null, null,
                org.springframework.data.domain.Sort.Direction.ASC, "id");

        assertThat(byName.getContent()).extracting(UserDto::getId).contains(account.getId());
    }
}
