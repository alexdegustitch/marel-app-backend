package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.account.PasswordPolicy;
import com.aleksandarparipovic.marel_app.account.UsernameRules;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferences;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferencesRepository;
import com.aleksandarparipovic.marel_app.user_session.UserSessionService;
import com.aleksandarparipovic.marel_app.user.dto.UserOptionDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final UserSessionService userSessionService;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;

    public UserDto getCurrentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        assert authentication != null;
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new EntityNotFoundException("User not found: " + username)
                );

        return UserMapper.toDto(user);
    }


    public UserDto getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new EntityNotFoundException("User not found: " + username)
                );

        return UserMapper.toDto(user);
    }

    public UserDto create(String username, String password, String email, String firstName, String lastName, String mobilePhone, String roleName) {

        if (!UsernameRules.isValid(username)) {
            throw new IllegalArgumentException(UsernameRules.requirement());
        }

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }

        if (userRepository.existsByEmailAddress(email)) {
            throw new IllegalArgumentException("Email already in use");
        }

        // An account an administrator creates gets the same password rules as one
        // somebody registers for themselves. The person who ends up living with
        // this password is not the one typing it.
        List<String> passwordProblems = PasswordPolicy.violations(password, username, email);
        if (!passwordProblems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", passwordProblems));
        }

        Role role = roleRepository.findByRoleNameIgnoreCase(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found"));

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .emailAddress(email)
                .firstName(firstName)
                .lastName(lastName)
                .mobilePhone(mobilePhone)
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build();

        User saved = userRepository.save(user);
        return UserMapper.toDto(saved);
    }

    public Page<UserDto> getUsers(
            int page,
            int size,
            String username,
            String search,
            String role,
            Long employeeId,
            Boolean active,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<User> spec = Specification.allOf();

        if (username != null && !username.isBlank()) {
            spec = spec.and(UserSpecifications.usernameContains(username));
        }

        if (search != null && !search.isBlank()) {
            spec = spec.and(UserSpecifications.matches(search));
        }

        if (role != null && !role.isBlank()) {
            spec = spec.and(UserSpecifications.hasRole(role));
        }

        if (employeeId != null) {
            spec = spec.and(UserSpecifications.hasEmployee(employeeId));
        }

        if (active != null) {
            spec = spec.and(UserSpecifications.isActive(active));
        }

        Page<UserDto> found = userRepository.findAll(spec, pageable).map(UserMapper::toDto);
        return enrichForDirectory(found);
    }

    /**
     * The two things the directory shows that are not on the user row: the
     * picture they chose, and whether they are here right now.
     *
     * <p>Both are looked up ONCE for the whole page. Asking per row is how a
     * twenty-five-row list becomes fifty queries, and presence in particular has
     * a batch query written for exactly this.
     *
     * <p>Only the paged directory calls this. Every other response that carries a
     * UserDto leaves both null, because nothing else displays them and reading
     * somebody's preferences to throw the answer away is work for nobody.
     */
    private Page<UserDto> enrichForDirectory(Page<UserDto> page) {
        List<Long> ids = page.getContent().stream().map(UserDto::getId).toList();
        if (ids.isEmpty()) {
            return page;
        }

        Set<Long> online = new HashSet<>(userSessionService.onlineUserIds(ids));

        Map<Long, String> avatars = new HashMap<>();
        for (UserPreferences preferences : userPreferencesRepository.findAllById(ids)) {
            JsonNode settings = preferences.getUiSettings();
            JsonNode avatar = settings == null ? null : settings.get("avatarKey");
            if (avatar != null && avatar.isTextual() && !avatar.asText().isBlank()) {
                avatars.put(preferences.getUserId(), avatar.asText());
            }
        }

        return page.map(user -> {
            user.setAvatarKey(avatars.get(user.getId()));
            user.setOnline(online.contains(user.getId()));
            return user;
        });
    }

    @Transactional(readOnly = true)
    public List<UserOptionDto> getActiveUserOptions(String userType) {
        List<User> users;

        if (userType == null || userType.isBlank()) {
            users = userRepository.findByActiveTrueAndArchivedAtIsNullOrderByFullNameAsc();
        } else {
            users = userRepository.findByActiveTrueAndArchivedAtIsNullAndRole_RoleNameIgnoreCaseOrderByFullNameAsc(userType);
        }

        return users
                .stream()
                .map(user -> new UserOptionDto(user.getId(), user.getUsername(), user.getFullName()))
                .toList();
    }

    @Transactional
    public UserDto update(Long id, UserUpdateRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        /*
         * A username is chosen once, at registration, and never again — not by its
         * owner and not by an administrator. It is what somebody signs in with and
         * what the audit trail says did a thing, and those rows carry no second
         * identifier to fall back on when it moves.
         *
         * Refused out loud rather than ignored: an administrator who types a new
         * username and watches nothing happen will try again.
         */
        if (request.getUsername() != null) {
            throw new IllegalArgumentException(
                    "Korisničko ime se ne može promeniti nakon kreiranja naloga.");
        }

        if (request.getEmailAddress() != null) {
            user.setEmailAddress(request.getEmailAddress());
        }

        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            user.setFirstName(request.getFirstName());
        }

        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            user.setLastName(request.getLastName());
        }

        if (request.getMobilePhone() != null) {
            user.setMobilePhone(request.getMobilePhone());
        }

        if (request.getDisplayName() != null) {
            String displayName = request.getDisplayName().trim();
            user.setDisplayName(displayName.isEmpty() ? null : displayName);
        }

        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            // The SAME rules a person gets when changing their own. A reset that
            // could set "1234" would make the policy advisory, and the account it
            // weakens is somebody else's.
            List<String> problems = PasswordPolicy.violations(
                    request.getPassword(), user.getUsername(), user.getEmailAddress());
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ", problems));
            }
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        if (request.getRoleName() != null) {
            Role role = roleRepository.findByRoleNameIgnoreCase(request.getRoleName())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found"));

            user.setRole(role);
        }

        applyEmployeeLink(user, request);

        // Flushed so the DB-generated full_name is re-read before mapping —
        // otherwise a rename answers with the new parts and the old full name.
        return UserMapper.toDto(userRepository.saveAndFlush(user));
    }


    @Transactional
    public void softDelete(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setArchivedAt(OffsetDateTime.now());

        userRepository.save(user);
    }

    @Transactional
    public void restore(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setArchivedAt(null);
    }



    /**
     * Say which worker an account belongs to.
     *
     * <p>Its own operation, not a field on {@link #update}, because a different
     * set of people may do it: supervisors know who on the floor is who, and are
     * meant to be able to answer this without also being able to set roles and
     * passwords. Two entry points, one rule — {@link #applyEmployeeLink} is what
     * both go through.
     */
    @Transactional
    public UserDto linkEmployee(Long userId, Long employeeId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));

        UserUpdateRequest request = new UserUpdateRequest();
        request.setEmployeeId(employeeId);
        applyEmployeeLink(user, request);

        return UserMapper.toDto(userRepository.save(user));
    }

    /** Cut the link. The account stays; it simply stops being a worker's. */
    @Transactional
    public UserDto unlinkEmployee(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));

        user.setEmployee(null);

        return UserMapper.toDto(userRepository.save(user));
    }

    /**
     * Say which worker this account is, or that it is none.
     *
     * <p>Kept apart from {@link #update} because it is the only field there that
     * can be wrong in a way that matters: getting a name or a phone number wrong
     * is a typo somebody corrects, while getting this wrong shows one person
     * another person's payslip. So it is checked rather than assigned.
     *
     * <p>Three refusals, all of them answered with a sentence instead of a
     * constraint violation the caller cannot read:
     * <ul>
     *   <li>both fields at once — null already means "leave it", so a request
     *       that says set-it AND clear-it has no single intention to carry out;
     *   <li>a worker who does not exist;
     *   <li>a worker who already has a different account. The database enforces
     *       this too (uq_users_employee_id); this is so the person doing it is
     *       told whose account is in the way.
     * </ul>
     */
    private void applyEmployeeLink(User user, UserUpdateRequest request) {
        boolean unlink = Boolean.TRUE.equals(request.getUnlinkEmployee());

        if (unlink && request.getEmployeeId() != null) {
            throw new IllegalArgumentException(
                    "Ne mogu istovremeno da povežem nalog sa radnikom i da raskinem vezu.");
        }

        if (unlink) {
            user.setEmployee(null);
            return;
        }

        if (request.getEmployeeId() == null) {
            return;
        }

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Radnik nije pronađen: " + request.getEmployeeId()));

        userRepository.findByEmployee_Id(employee.getId())
                .filter(existing -> !existing.getId().equals(user.getId()))
                .ifPresent(existing -> {
                    throw new ConflictException("Radnik " + employee.getFullName()
                            + " je već povezan sa nalogom „" + existing.getUsername() + "”.");
                });

        user.setEmployee(employee);
    }
}
