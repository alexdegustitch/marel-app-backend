package com.aleksandarparipovic.marel_app.role;

import com.aleksandarparipovic.marel_app.role.dto.RoleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    public List<RoleDto> findAll() {
        return roleRepository.findAll().stream().map(RoleMapper::toDto).toList();
    }

    public RoleDto create(String name) {

        if (roleRepository.existsByRoleNameIgnoreCase(name)) {
            throw new IllegalArgumentException("Role already exists: " + name);
        }

        Role role = Role.builder()
                .roleName(name.trim())
                .build();

        Role saved = roleRepository.save(role);

        return RoleMapper.toDto(saved);
    }
}
