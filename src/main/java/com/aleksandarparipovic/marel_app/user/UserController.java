package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.user.dto.UserCreateRequest;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "id") String sortBy
    ) {
        Page<UserDto> result = userService.getUsers(page,size,username,role, active, direction,sortBy);

        return ResponseEntity.ok(result);
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
                        req.getFullName(),
                        req.getRole()
                )
        );
    }

    @GetMapping("/{username}")
    public ResponseEntity<UserDto> getUserByUsername(@PathVariable String username) {
        return ResponseEntity.ok(userService.getUserByUsername(username));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return ResponseEntity.ok(userService.update(id, request));
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
