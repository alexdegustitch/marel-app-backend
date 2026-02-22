package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee.specification.EmployeeSpecifications;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository repository;
    private final DepartmentRepository departmentRepository;
    private final BonusCategoryRepository bonusCategoryRepository;
    private final EmployeeBonusRepository employeeBonusRepository;
    private final EmployeeMapper mapper;

    @Transactional
    public Employee create(Employee e) {
        if (repository.existsByEmployeeNo(e.getEmployeeNo()))
            throw new IllegalStateException("Employee number already exists");

        e.setId(null);
        return repository.save(e);
    }

    @Transactional
    public EmployeeWithBonusView  createEmployee(EmployeeCreateRequest request){
        Employee employee = new Employee();
        employee.setEmployeeNo(request.getEmployeeNo());
        employee.setFullName(request.getFullName());

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new EntityNotFoundException("Department not found"));

        employee.setDepartment(department);
        employee.setForeigner(request.getForeigner());
        employee.setTransportAllowanceRsd(request.getTransportAllowanceRsd());
        employee.setEmploymentStartDate(request.getEmploymentStartDate());
        employee.setNotes(request.getNotes());

        employee = repository.save(employee);

        BonusCategory category = bonusCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new EntityNotFoundException("Bonus category not found"));


        EmployeeBonus newBonus = new EmployeeBonus();
        newBonus.setEmployee(employee);
        newBonus.setBonusCategory(category);
        newBonus.setStartDate(LocalDate.now());

        employeeBonusRepository.save(newBonus);

        return repository.findEmployeeWithBonusById(employee.getId())
                .orElseThrow(() -> new EntityNotFoundException("Employee projection not found"));
    }

    public Page<EmployeeWithBonusView> search(SearchRequest state) {

        Specification<Employee> spec =
                EmployeeSpecifications.fromSearchRequest(state);

        Pageable pageable =
                PageableBuilder.from(state);

        return repository.searchWithBonus(spec, pageable);
    }


    public <T> Page<T> searchAll(SearchRequest request, Class<T> projectionType) {
        Specification<Employee> spec = EmployeeSpecifications.fromSearchRequest(request);
        Pageable pageable = PageableBuilder.from(request);
        return repository.searchWithProjection(spec, pageable, projectionType);
    }


    public List<EmployeeDto> search(Boolean active, Long departmentId, LocalDate employedOn) {

        Specification<Employee> spec = Specification
                .where(EmployeeSpecifications.notArchived())
                .and(EmployeeSpecifications.isActive(active))
                .and(EmployeeSpecifications.inDepartment(departmentId))
                .and(EmployeeSpecifications.employedOn(employedOn));

        return repository.findAll(spec)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public EmployeeWithBonusView updateEmployee(Long id, EmployeeEditRequest request) {

        Employee employee = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));

        employee.updateFrom(request); // ONLY scalar fields

        updateDepartmentIfChanged(employee, request);
        updateEmployeeBonus(employee, request);

        return repository.findEmployeeWithBonusById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee projection not found"));

    }

    private void updateDepartmentIfChanged(Employee employee, EmployeeEditRequest request) {

        if (!employee.getDepartment().getId().equals(request.getDepartmentId())) {

            Department department = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> new EntityNotFoundException("Department not found"));

            employee.setDepartment(department);
        }
    }


    private void updateEmployeeBonus(Employee employee, EmployeeEditRequest request) {

        if (request.getCategoryId() == null) {
            return;
        }

        BonusCategory category = bonusCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new EntityNotFoundException("Bonus category not found"));

        // Example: assume only ONE active bonus per employee
        Optional<EmployeeBonus> currentBonus = employeeBonusRepository.findActiveBonus(employee.getId(), request.getCategoryId());
        System.out.println("Employee is: " + currentBonus + " and: " + employee.getId() + ", " + request.getCategoryId());
        if (currentBonus.isEmpty()) {
            // Create new bonus
            EmployeeBonus newBonus = new EmployeeBonus();
            newBonus.setEmployee(employee);
            newBonus.setBonusCategory(category);
            newBonus.setStartDate(LocalDate.now());

            employeeBonusRepository.save(newBonus);
        }
    }

    public Page<EmployeeWithBonusView> getEmployeeBonusTable(Pageable pageable) {
        return repository.findEmployeesWithCurrentBonus(pageable);
    }


    @Transactional
    public Employee update(Long id, Employee updated) {
        Employee e = repository.findById(id).orElseThrow();

        e.setFullName(updated.getFullName());
        e.setDepartment(updated.getDepartment());
        e.setEmploymentStartDate(updated.getEmploymentStartDate());
        e.setEmploymentEndDate(updated.getEmploymentEndDate());
        e.setActive(updated.isActive());
        e.setForeigner(updated.isForeigner());
        e.setNormGraceDays(updated.getNormGraceDays());
        e.setProbationEndDate(updated.getProbationEndDate());
        e.setTransportAllowanceRsd(updated.getTransportAllowanceRsd());
        e.setNotes(updated.getNotes());

        return repository.save(e);
    }

    @Transactional
    public void archive(Long id) {
        Employee e = repository.findById(id).orElseThrow();
        e.setArchivedAt(OffsetDateTime.now());
        e.setActive(false);
        repository.save(e);
    }
}
