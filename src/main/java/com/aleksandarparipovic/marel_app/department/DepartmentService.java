package com.aleksandarparipovic.marel_app.department;

import com.aleksandarparipovic.marel_app.department.dto.DepartmentCreateRequest;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper mapper;

    @Transactional
    public DepartmentDto createDepartment(DepartmentCreateRequest request) {

        if (departmentRepository.existsByNameIgnoreCase(request.getName().trim())) {
            throw new IllegalArgumentException("Department with this name already exists");
        }

        Department department = Department.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .active(true)
                .build();

        Department saved = departmentRepository.save(department);

        return mapper.toDto(saved); // ✅
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> getAllDepartments() {
        return departmentRepository.findAll()
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentOptionDto> getAllActiveDepartments(){
        return departmentRepository.findByActiveTrueOrderByNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<DepartmentDto> getDepartments(
            String name,
            Boolean active,
            int page,
            int size,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<Department> spec = Specification.allOf();

        if (name != null && !name.isBlank()) {
            spec = spec.and(DepartmentSpecifications.nameContains(name));
        }
        if (active != null) {
            spec = spec.and(DepartmentSpecifications.isActive(active));
        }


        return departmentRepository.findAll(spec, pageable).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public DepartmentDto getDepartment(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Department not found"));
        return mapper.toDto(department);
    }

    @Transactional
    public DepartmentDto updateDepartment(Long id, DepartmentUpdateRequest request) {

        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Department not found"));

        if (request.getName() != null) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(department.getName())
                    && departmentRepository.existsByNameIgnoreCase(newName)) {
                throw new IllegalArgumentException("Department with this name already exists");
            }
            department.setName(newName);
        }

        if (request.getDescription() != null) {
            department.setDescription(request.getDescription());
        }

        if (request.getActive() != null) {
            department.setActive(request.getActive());
            // archived_at will be handled by DB trigger if you configured it
        }

        departmentRepository.save(department);

        return mapper.toDto(department);
    }

    @Transactional
    public void softDelete(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Department not found"));

        department.setActive(false);
        departmentRepository.save(department);
    }

    @Transactional
    public void restore(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Department not found"));

        department.setActive(true);
        // archived_at can be cleared via trigger or here if you want:
        // department.setArchivedAt(null);

        departmentRepository.save(department);
    }
}
