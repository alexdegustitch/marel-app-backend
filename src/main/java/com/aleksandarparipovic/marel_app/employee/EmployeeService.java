package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository;
import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.dto.ArchiveEmployeeRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeBasicInfoDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDetailDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee.specification.EmployeeSpecifications;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.CompensationSchemeInitializer;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private final EmployeeRepository repository;
    private final DepartmentRepository departmentRepository;
    private final BonusCategoryRepository bonusCategoryRepository;
    private final EmployeeBonusRepository employeeBonusRepository;
    private final EmployeeMapper mapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final CompensationSchemeInitializer compensationSchemeInitializer;
    private final EmployeePayrollValueService employeePayrollValueService;
    private final CurrentUserService currentUserService;


    public EmployeeBasicInfoDto getEmployeeById(Long employeeId){
        Employee employee = repository.findById(employeeId)
                .orElseThrow(()-> new EntityNotFoundException("Employee not found"));

        return mapper.toBasicInfoDto(employee);
    }

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
        if (request.getTransportAllowanceMode() != null) {
            employee.setTransportAllowanceMode(request.getTransportAllowanceMode());
        }
        employee.setEmploymentStartDate(request.getEmploymentStartDate());
        employee.setNotes(request.getNotes());

        employee = repository.save(employee);

        // Every employee needs a compensation scheme from their first day, or the
        // first work log recorded for them is rejected and their first recalc job
        // fails. STANDARD, deliberately, and regardless of is_foreigner: the
        // restricted policy is assigned explicitly by an administrator, never
        // inferred from a personnel attribute.
        compensationSchemeInitializer.assignInitialScheme(employee);

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

        // This form has no effective-date field, so the rate applies from the first
        // of the current month. Recorded here for the same reason as in
        // patchEmployee: until phase 7 drops employees.hourly_rate, both write paths
        // have to reach the history, or whichever one skipped it would be silently
        // overruled by the other on the next recalculation.
        recordHourlyRate(id, employee.getHourlyRate(), null);

        return repository.findEmployeeWithBonusById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee projection not found"));

    }

    /**
     * Write a rate change where payroll actually reads it: the value history.
     *
     * <p>{@code employees.hourly_rate} is no longer what prices a payroll item.
     * {@code PayrollRunItemService.hourlyRateFor} resolves HOURLY_RATE in force on
     * the payroll month's START DATE and only falls back to the column when the
     * employee has no history at all. Writing the column alone therefore looked
     * like it worked and was then reverted by the next recalculation, for exactly
     * those employees who had a history row.
     *
     * <p>The old code also rewrote {@code payroll_run_items.hourly_rate} directly
     * for every open month. That is what the history exists to prevent: it
     * repriced months the rate was never in force for. Marking the items for
     * recalculation gets the correct outcome instead — each one re-resolves the
     * rate for ITS OWN month, so an earlier month keeps the earlier rate. It also
     * repairs items that the old behaviour had already overwritten.
     *
     * <p>Items are marked whatever the effective date, deliberately: a backdated
     * correction changes older months too, and only the recalculation itself can
     * tell which. Locked items are excluded by the query.
     */
    private void recordHourlyRate(Long employeeId, BigDecimal rate, LocalDate effectiveFrom) {
        if (rate == null) {
            return;
        }
        LocalDate from = effectiveFrom != null
                ? effectiveFrom
                : LocalDate.now().withDayOfMonth(1);

        boolean recorded = employeePayrollValueService.setValue(
                employeeId, EmployeePayrollValueCodes.HOURLY_RATE, rate, from,
                null, currentUserService.getCurrentUserId()).isPresent();

        if (!recorded) {
            return; // The rate in force from that date was already this one.
        }

        int marked = payrollRunItemRepository.markNeedsRecalculationByEmployeeId(employeeId);
        log.info("Employee {} hourly rate set to {} from {}; {} unlocked payroll item(s) "
                + "marked for recalculation", employeeId, rate, from, marked);
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

    @Transactional
    public void archiveEmployee(Long id, ArchiveEmployeeRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        com.aleksandarparipovic.marel_app.user.User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Authenticated user not found"));

        if (!passwordEncoder.matches(req.getPassword(), currentUser.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid password");
        }

        Employee employee = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));

        employee.setArchivedAt(OffsetDateTime.now());
        employee.setActive(false);
        repository.save(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDetailDto getEmployeeDetail(Long id) {
        Employee employee = repository.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));
        return new EmployeeDetailDto(employee);
    }

    @Transactional
    public EmployeeWithBonusView patchEmployee(Long id, EmployeePatchRequest req) {
        Employee employee = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));

        if (req.getEmployeeNo() != null) employee.setEmployeeNo(req.getEmployeeNo());
        if (req.getFullName() != null) employee.setFullName(req.getFullName());
        if (req.getForeigner() != null) employee.setForeigner(req.getForeigner());
        if (req.getTransportAllowanceRsd() != null) employee.setTransportAllowanceRsd(req.getTransportAllowanceRsd());
        if (req.getTransportAllowanceMode() != null)
        {
            if(req.getTransportAllowanceMode().equals("FIXED")) {
                employee.setTransportAllowanceRsd(req.getTransportAllowanceRsd());
            }else{
                employee.setTransportAllowanceMode(null);
            }
            employee.setTransportAllowanceMode(req.getTransportAllowanceMode());
        }
        if (req.getEmploymentStartDate() != null)  employee.setEmploymentStartDate(req.getEmploymentStartDate());
        if (req.getEmploymentEndDate() != null)    employee.setEmploymentEndDate(req.getEmploymentEndDate());
        if (req.getActive() != null)               employee.setActive(req.getActive());
        if (req.getNormGraceDays() != null)        employee.setNormGraceDays(req.getNormGraceDays());
        if (req.getNotes() != null)                employee.setNotes(req.getNotes());
        if (req.getWorksInCommercial() != null)     employee.setWorksInCommercial(req.getWorksInCommercial());
        if (req.getMobilePhone() != null)          employee.setMobilePhone(req.getMobilePhone());
        if (req.getPreferredLocale() != null) {
            // Validated against the supported set rather than passed through: the
            // column has a CHECK constraint, and a raw constraint violation is a
            // 500 the user cannot act on.
            if (!AppLocales.SUPPORTED.contains(req.getPreferredLocale())) {
                throw new IllegalArgumentException(
                        "Nepodržan jezik: " + req.getPreferredLocale()
                                + ". Dozvoljeni su: " + String.join(", ", AppLocales.SUPPORTED) + ".");
            }
            employee.setPreferredLocale(req.getPreferredLocale());
        }

        if (req.getHourlyRate() != null) {
            employee.setHourlyRate(req.getHourlyRate());
        }

        if(req.getDefaultWorkCategoryId() != null) {
            WorkCodeCategory category = workCodeCategoryRepository.findById(req.getDefaultWorkCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Work code category not found: " + req.getDefaultWorkCategoryId()));
            employee.setDefaultWorkCategory(category);
        }

        if (req.getDepartmentId() != null) {
            Department department = departmentRepository.findById(req.getDepartmentId())
                    .orElseThrow(() -> new EntityNotFoundException("Department not found: " + req.getDepartmentId()));
            employee.setDepartment(department);
        }

        if (req.getCategoryId() != null) {
            BonusCategory category = bonusCategoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Bonus category not found: " + req.getCategoryId()));
            boolean alreadyActive = employeeBonusRepository.findActiveBonus(employee.getId(), req.getCategoryId()).isPresent();
            if (!alreadyActive) {
                EmployeeBonus newBonus = new EmployeeBonus();
                newBonus.setEmployee(employee);
                newBonus.setBonusCategory(category);
                newBonus.setStartDate(LocalDate.now());
                employeeBonusRepository.save(newBonus);
            }
        }

        repository.save(employee);

        recordHourlyRate(id, req.getHourlyRate(), req.getHourlyRateEffectiveFrom());

        return repository.findEmployeeWithBonusById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));
    }
}
