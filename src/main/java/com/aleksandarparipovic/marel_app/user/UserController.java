package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.user.dto.UserCreateRequest;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user.dto.UserOptionDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
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
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "id") String sortBy
    ) {
        Page<UserDto> result =
                userService.getUsers(page, size, username, search, role, employeeId, active, direction, sortBy);

        return ResponseEntity.ok(result);
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
