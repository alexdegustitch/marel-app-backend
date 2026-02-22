package com.aleksandarparipovic.marel_app.role;

import com.aleksandarparipovic.marel_app.role.dto.RoleCreateRequest;
import com.aleksandarparipovic.marel_app.role.dto.RoleDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public List<RoleDto> getAll() {
        return roleService.findAll();
    }

    @PostMapping
    public ResponseEntity<RoleDto> create(@RequestBody @Valid RoleCreateRequest req) {
        return ResponseEntity.ok(
                roleService.create(req.getName())
        );
    }


}
