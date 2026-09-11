package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.user.dto.UserCreateRequest;
import com.aleksandarparipovic.marel_app.user.dto.UserDirectoryStatsDto;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.mailing_list.MailingListService;
import com.aleksandarparipovic.marel_app.mailing_list.dto.UserMailingListDto;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user.dto.UserOptionDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
import com.aleksandarparipovic.marel_app.user.dto.UserWorkContextDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserWorkContextService userWorkContextService;
    private final MailingListService mailingListService;
    private final CurrentUserService currentUserService;

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser() {
        return ResponseEntity.ok(userService.getCurrentUser());
    }

    @GetMapping
    public ResponseEntity<Page<UserDto>> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            /** One box over name, username and e-mail — what the directory searches with. */
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            /** Whose account is this worker's. Zero rows or one — never more. */
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Boolean active,
            /** Only the people at the application right now — the "na vezi" tile. */
            @RequestParam(required = false) Boolean online,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "id") String sortBy
    ) {
        Page<UserDto> result =
                userService.getUsers(page, size, username, search, role, employeeId, active, online, direction, sortBy);

        return ResponseEntity.ok(result);
    }

    /** The directory's tile figures: total, online, and the split by role. */
    @GetMapping("/stats")
    public ResponseEntity<UserDirectoryStatsDto> getDirectoryStats() {
        return ResponseEntity.ok(userService.getDirectoryStats());
    }

    @GetMapping("/active-users")
    public ResponseEntity<List<UserOptionDto>> getActiveUserOptions(
            @RequestParam(required = false) String userType
    ) {
        return ResponseEntity.ok(userService.getActiveUserOptions(userType));
    }

    @PostMapping
    public ResponseEntity<UserDto> create(
            @RequestBody @Valid UserCreateRequest req
            ) {
        return ResponseEntity.ok(
                userService.create(
                        req.getUsername(),
                        req.getPassword(),
                        req.getEmailAddress(),
                        req.getFirstName(),
                        req.getLastName(),
                        req.getMobilePhone(),
                        req.getRole()
                )
        );
    }

    @GetMapping("/{username}")
    public ResponseEntity<UserDto> getUserByUsername(@PathVariable String username) {
        return ResponseEntity.ok(userService.getUserByUsername(username));
    }

    /**
     * One account, by its numeric id — the colleague profile everyone may open.
     *
     * <p>Its OWN route, and its own security rule, kept apart from
     * {@link #getUserByUsername} above. That one reads by name and falls through
     * to the admin rule in {@code SecurityConfig}; this one is opened to every
     * signed-in person, because the profile page a colleague clicks to is the
     * read-only face of the directory — the same name, role, e-mail and telephone
     * the directory already shows everyone, addressed by the id the directory
     * links with. It carries no password material, no payroll, no settings.
     *
     * <p>Nested under {@code /id/} rather than sharing the one-segment
     * {@code /{username}} route so the two never collide and the security rule can
     * name exactly this shape ({@code GET /api/users/id/*}) without widening
     * by-name reads.
     */
    @GetMapping("/id/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    /**
     * The work-status slice of a colleague's profile: department, since when, and
     * whether the person is at work today. Same audience as the profile itself —
     * everyone signed in — because it carries no pay and nothing the reader may
     * change, only what a colleague needs to know to reach them.
     *
     * <p>{@code 204 No Content} when the account is not a worker's: an office
     * account has no work life to state, and the caller shows the account-level
     * facts alone rather than an empty "Organizacija".
     */
    @GetMapping("/id/{id}/work-status")
    public ResponseEntity<UserWorkContextDto> getWorkStatus(@PathVariable Long id) {
        UserWorkContextDto context = userWorkContextService.forUser(id);
        return context == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(context);
    }

    /**
     * The mailing lists this colleague is on — but only the ones the CALLER may
     * also see. The intersection ("zajedničke" liste) is enforced in the mailing
     * service against the signed-in user, so a profile can never enumerate somebody
     * else's private lists. Same open audience as the rest of the profile.
     */
    @GetMapping("/id/{id}/mailing-lists")
    public ResponseEntity<List<UserMailingListDto>> getMailingLists(@PathVariable Long id) {
        return ResponseEntity.ok(
                mailingListService.listsUserBelongsTo(id, currentUserService.getCurrentUserId()));
    }

    /**
     * Edit an account: names, e-mail, role, password, active.
     *
     * <p>The role rule is stated HERE as well as in {@code SecurityConfig}, on
     * purpose. This method can set {@code roleName} and {@code password}, so a
     * caller who reached it without being an administrator could make themselves
     * one. Until now the only thing standing in the way was a URL pattern —
     * {@code /api/users/**} — which sits one edit away from a rule added for
     * something else and is invisible from this file. Two layers, because the
     * cost of the pattern being wrong once is somebody else's account.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    /**
     * Say which worker this account belongs to.
     *
     * <p>Separate from {@link #updateUser} on purpose, and guarded by its own
     * capability. Supervisors hold {@code USER_EMPLOYEE_LINK} because they are
     * the ones who know who on the floor is who; they do NOT hold the right to
     * edit accounts, and widening the general PATCH to them would have handed
     * them roles and passwords along with it.
     *
     * <p>PUT rather than PATCH because the body carries the whole answer: this
     * account is that worker. Repeating it changes nothing, which is what a
     * screen that saves a form twice needs.
     */
    @PutMapping("/{id}/employee")
    @PreAuthorize("@perm.has('USER_EMPLOYEE_LINK')")
    public ResponseEntity<UserDto> linkEmployee(
            @PathVariable Long id,
            @RequestBody @Valid EmployeeLinkRequest request
    ) {
        return ResponseEntity.ok(userService.linkEmployee(id, request.employeeId()));
    }

    /** Cut the link. The account stays; it simply stops being a worker's. */
    @DeleteMapping("/{id}/employee")
    @PreAuthorize("@perm.has('USER_EMPLOYEE_LINK')")
    public ResponseEntity<UserDto> unlinkEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(userService.unlinkEmployee(id));
    }

    /** Which worker. Never null — removing the link is the DELETE above. */
    public record EmployeeLinkRequest(@jakarta.validation.constraints.NotNull Long employeeId) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    public ResponseEntity<Void> restoreUser(@PathVariable Long id) {
        userService.restore(id);
        return ResponseEntity.noContent().build();
    }

}
