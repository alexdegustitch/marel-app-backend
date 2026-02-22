package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
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
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
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

    public UserDto create(String username, String password, String email, String fullName, String roleName) {

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }

        if (userRepository.existsByEmailAddress(email)) {
            throw new IllegalArgumentException("Email already in use");
        }

        Role role = roleRepository.findByRoleNameIgnoreCase(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found"));

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .emailAddress(email)
                .fullName(fullName)
                .role(role)
                .active(true)
                .build();

        User saved = userRepository.save(user);
        return UserMapper.toDto(saved);
    }

    public Page<UserDto> getUsers(
            int page,
            int size,
            String username,
            String role,
            Boolean active,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<User> spec = Specification.allOf();

        if (username != null && !username.isBlank()) {
            spec = spec.and(UserSpecifications.usernameContains(username));
        }

        if (role != null && !role.isBlank()) {
            spec = spec.and(UserSpecifications.hasRole(role));
        }

        if (active != null) {
            spec = spec.and(UserSpecifications.isActive(active));
        }

        return userRepository.findAll(spec, pageable)
                .map(UserMapper::toDto);
    }

    @Transactional
    public UserDto update(Long id, UserUpdateRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            user.setUsername(request.getUsername());
        }

        if (request.getEmailAddress() != null) {
            user.setEmailAddress(request.getEmailAddress());
        }

        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        if (request.getRoleName() != null) {
            Role role = roleRepository.findByRoleNameIgnoreCase(request.getRoleName())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found"));

            user.setRole(role);
        }

        return UserMapper.toDto(userRepository.save(user));
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


}
