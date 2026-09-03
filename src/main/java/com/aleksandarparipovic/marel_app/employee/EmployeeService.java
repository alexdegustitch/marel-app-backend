package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.department_head.EmployeeRowEnricher;
import com.aleksandarparipovic.marel_app.department_head.DepartmentHeadPeriod;
import com.aleksandarparipovic.marel_app.department_head.DepartmentHeadPeriodRepository;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository;
import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.employment_period.EmploymentPeriodService;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.dto.ArchiveEmployeeRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeBasicInfoDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDetailDto;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDirectorySummary;
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
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
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
    private final EmploymentPeriodService employmentPeriodService;
    private final EmployeePayrollValueService employeePayrollValueService;
    private final CurrentUserService currentUserService;
    private final CompensationSchemeRepository schemeRepository;
    private final DepartmentHeadPeriodRepository departmentHeadPeriodRepository;
    private final ShiftRepository shiftRepository;
    private final com.aleksandarparipovic.marel_app.recalc_queue.AffectedMonthsRecalculator employeeRowRecalculator;
    private final com.aleksandarparipovic.marel_app.employee_work_category.EmployeeWorkCategoryService employeeWorkCategoryService;
    private final EmployeeRowEnricher employeeRowEnricher;
    private final EmployeeRecordService employeeRecordService;


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
        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new EntityNotFoundException("Department not found"));

        employee.setDepartment(department);
        employee.setEmail(request.getEmail());
        employee.setTransportAllowanceRsd(request.getTransportAllowanceRsd());
        if (request.getTransportAllowanceMode() != null) {
            employee.setTransportAllowanceMode(request.getTransportAllowanceMode());
        }
        employee.setEmploymentStartDate(request.getEmploymentStartDate());
        employee.setNotes(request.getNotes());
        employee.setMobilePhone(request.getMobilePhone());
        employee.setHourlyRate(request.getHourlyRate());
        if (request.getNormGraceDays() != null) {
            employee.setNormGraceDays(request.getNormGraceDays());
        }
        if (request.getPreferredLocale() != null) {
            // Same check and same message as patchEmployee: isSupported (not a
            // case-sensitive set lookup), then store normalize()'s canonical
            // spelling so the column cannot collect casing variants.
            if (!AppLocales.isSupported(request.getPreferredLocale())) {
                throw new IllegalArgumentException(
                        "Nepodržan jezik: " + request.getPreferredLocale()
                                + ". Dozvoljeni su: " + String.join(", ", AppLocales.SUPPORTED) + ".");
            }
            employee.setPreferredLocale(AppLocales.normalize(request.getPreferredLocale()));
        }

        employee = repository.save(employee);

        // Every employee needs a compensation scheme from their first day, or the
        // first work log recorded for them is rejected and their first recalc job
        // fails. The scheme is now CHOSEN on the form rather than defaulted: it
        // decides which categories are usable and whether a performance bonus is
        // earned at all, so it is a payroll decision and the administrator makes
        // it explicitly.
        compensationSchemeInitializer.assignInitialScheme(employee, request.getCompensationSchemeId());

        // The first spell of employment, opened with the employee's own
        // norm_grace_days — the employee-level default (30) applies to the FIRST
        // period only; a later one defaults to zero, because a returning employee
        // serves no new probation unless somebody says so.
        employmentPeriodService.openFirstPeriod(employee);

        // The bonus category is meaningful only under a scheme that earns a
        // performance bonus. Under one that does not, the form disables the field
        // and sends nothing — so require it here rather than with @NotNull, which
        // cannot see which scheme was chosen.
        CompensationScheme chosenScheme = schemeRepository.findById(request.getCompensationSchemeId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Način obračuna ne postoji: " + request.getCompensationSchemeId()));
        boolean bonusApplies = Boolean.TRUE.equals(chosenScheme.getAllowsPerformanceBonus());

        if (bonusApplies) {
            if (request.getCategoryId() == null) {
                throw new IllegalArgumentException("Kategorija bonusa je obavezna.");
            }

            BonusCategory category = bonusCategoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Bonus category not found"));

            EmployeeBonus newBonus = new EmployeeBonus();
            newBonus.setEmployee(employee);
            newBonus.setBonusCategory(category);
            newBonus.setStartDate(LocalDate.now());

            employeeBonusRepository.save(newBonus);
        }

        employeeWorkCategoryService.openFirstPeriod(
                employee, request.getDefaultWorkCategoryId(), employee.getEmploymentStartDate());

        if (request.getDepartmentHead() != null) {
            openDepartmentHeadPeriod(employee, department,
                    request.getCompensationSchemeId(), request.getDepartmentHead());
        }

        /*
         * THE KARTON FOR THE MONTH THEY STARTED.
         *
         * Until now a new employee had none until somebody pressed "Kreiraj
         * kartone" for that month or recorded their first shift — so between
         * being entered and being worked they were missing from the month they
         * belong to.
         *
         * The month is EMPLOYMENT START, not today: an employee entered in
         * advance or backdated belongs to the month they began, and that is the
         * month every other record of theirs is filed under.
         *
         * Through getOrCreateMonthlyRecord, which does NOT publish
         * PayrollMonthInitEvent — deliberately, unlike the bulk creation.
         * Adding one person to the register must not initialise a whole month's
         * payroll as a side effect; that stays something somebody decides.
         *
         * Idempotent: the method reads before it writes, and
         * uq_employee_records_employee_start_date is behind it either way.
         */
        employeeRecordService.getOrCreateMonthlyRecord(
                employee.getId(), employee.getEmploymentStartDate());

        return employeeRowEnricher.enrich(repository.findEmployeeWithBonusById(employee.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Employee projection not found"));
    }

    /**
     * Make a newly created employee head of their own department.
     *
     * <p>Refused for a scheme that earns no performance bonus. That is the rule
     * the owner asked for — "only a standard worker can be head" — expressed
     * through {@code allows_performance_bonus} rather than by naming STANDARD,
     * FOREIGN_FIXED_COEFFICIENT and COMMERCIAL here: naming a scheme in Java is
     * what the business rules forbid (§10), and a scheme added tomorrow gets the
     * right answer with no code change.
     *
     * <p>Overlap is left to {@code ex_dhp_no_overlap}. A check-then-insert here
     * would be a race two concurrent requests could both pass.
     */
    private void openDepartmentHeadPeriod(Employee employee,
                                          Department department,
                                          Long compensationSchemeId,
                                          EmployeeCreateRequest.DepartmentHeadOnCreate head) {

        CompensationScheme scheme = schemeRepository.findById(compensationSchemeId)
                .orElseThrow(() -> new EntityNotFoundException("Način obračuna ne postoji"));

        if (!Boolean.TRUE.equals(scheme.getAllowsPerformanceBonus())) {
            throw new IllegalStateException(
                    "Radnik na načinu obračuna \"" + scheme.getName()
                            + "\" ne može biti šef odeljenja.");
        }

        Shift shift = head.getShiftId() == null ? null
                : shiftRepository.findById(head.getShiftId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Smena ne postoji: " + head.getShiftId()));

        departmentHeadPeriodRepository.save(DepartmentHeadPeriod.builder()
                .department(department)
                .employee(employee)
                .shift(shift)
                .validFrom(head.getValidFrom())
                .validTo(head.getValidTo())
                .note("Opened with the employee.")
                .build());
    }

    @Transactional(readOnly = true)
    public Page<EmployeeWithBonusView> search(SearchRequest state) {

        Specification<Employee> spec =
                EmployeeSpecifications.fromSearchRequest(state);

        Pageable pageable =
                PageableBuilder.from(state);

        return employeeRowEnricher.enrich(repository.searchWithBonus(spec, pageable));
    }

    /**
     * The figures the directory shows above its table, for the same filters
     * the table is showing. A scheme filter in the request is dropped on
     * purpose: the tiles say how many people are on EACH scheme, which is only
     * an answer if the question was not already narrowed to one of them.
     */
    @Transactional(readOnly = true)
    public EmployeeDirectorySummary directorySummary(SearchRequest request) {
        SearchRequest withoutScheme = new SearchRequest();
        withoutScheme.setGlobalSearch(request == null ? null : request.getGlobalSearch());
        if (request != null && request.getFilters() != null) {
            withoutScheme.setFilters(request.getFilters().stream()
                    .filter(f -> f == null || !"schemeCode".equals(f.getField()))
                    .toList());
        }
        Specification<Employee> spec = EmployeeSpecifications.fromSearchRequest(withoutScheme);
        return repository.directorySummary(spec, LocalDate.now());
    }


    // Read-only, and not only as documentation: the enricher loads value-history
    // ENTITIES for every row on the page, and outside a read-only transaction
    // Hibernate dirty-checks each of them at the end of the request for a write
    // that never happens.
    @Transactional(readOnly = true)
    public <T> Page<T> searchAll(SearchRequest request, Class<T> projectionType) {
        Specification<Employee> spec = EmployeeSpecifications.fromSearchRequest(request);
        Pageable pageable = PageableBuilder.from(request);
        Page<T> page = repository.searchWithProjection(spec, pageable, projectionType);
        if (projectionType.equals(EmployeeWithBonusView.class)) {
            @SuppressWarnings("unchecked")
            Page<EmployeeWithBonusView> rows = (Page<EmployeeWithBonusView>) page;
            employeeRowEnricher.enrich(rows);
        }
        return page;
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

        return employeeRowEnricher.enrich(repository.findEmployeeWithBonusById(id))
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
        return employeeRowEnricher.enrich(repository.findEmployeesWithCurrentBonus(pageable));
    }


    @Transactional
    public Employee update(Long id, Employee updated) {
        Employee e = repository.findById(id).orElseThrow();

        e.setFirstName(updated.getFirstName());
        e.setLastName(updated.getLastName());
        e.setDepartment(updated.getDepartment());
        // Employment dates are written to the CURRENT period, not here: these
        // columns are a trigger-maintained mirror of it, so setting them directly
        // would be overwritten by the next period change and disagree with the
        // periods until then.
        employmentPeriodService.applyEditedDates(
                e.getId(), updated.getEmploymentStartDate(), updated.getEmploymentEndDate());
        e.setActive(updated.isActive());
        e.setEmail(updated.getEmail());
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

        LocalDate endedOn = req.getEmploymentEndDate() != null ? req.getEmploymentEndDate() : LocalDate.now();

        if (employee.getEmploymentStartDate() != null && endedOn.isBefore(employee.getEmploymentStartDate())) {
            throw new IllegalArgumentException(
                    "Datum prestanka rada ne može biti pre početka rada ("
                            + employee.getEmploymentStartDate() + ").");
        }

        // Closes the open spell in employee_employment_periods, which is the
        // authority; employees.employment_end_date is a trigger-maintained mirror
        // of it and must not be written directly.
        employmentPeriodService.applyEditedDates(employee.getId(), null, endedOn);

        // now(), not endedOn: this records when the row was hidden, not when the
        // person stopped working. The second question is answered by the period.
        employee.setArchivedAt(OffsetDateTime.now());
        employee.setActive(false);
        repository.save(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDetailDto getEmployeeDetail(Long id) {
        Employee employee = repository.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));
        return new EmployeeDetailDto(employee, employmentPeriodService.canEditEmploymentStart(id));
    }

    @Transactional
    public EmployeeWithBonusView patchEmployee(Long id, EmployeePatchRequest req) {
        Employee employee = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));

        /*
         * Read BEFORE anything is written: what matters is the TRANSITION, and
         * once setActive has run there is nothing left to compare against.
         *
         * This is the only live path that can turn `active` back on — the PUT
         * form (EmployeeEditRequest) carries no such field, and archive() only
         * ever turns it off.
         */
        boolean wasInactive = !employee.isActive();

        // Assigned once, at creation. It is the identifier the shifts, records and
        // payroll of this employee are filed under, and the screens print it as
        // the thing you look somebody up by — so it is not a field that gets
        // corrected later. Unchanged is still accepted: the employee screen
        // sends the whole form on every save.
        if (req.getEmployeeNo() != null && !req.getEmployeeNo().equals(employee.getEmployeeNo())) {
            throw new IllegalArgumentException(
                    "Šifra radnika se ne menja — dodeljuje se pri kreiranju radnika.");
        }
        if (req.getFirstName() != null) employee.setFirstName(req.getFirstName());
        if (req.getLastName() != null) employee.setLastName(req.getLastName());
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
        // Straight to the period; employees.* are the trigger's mirror of it.
        employmentPeriodService.applyEditedDates(
                employee.getId(), req.getEmploymentStartDate(), req.getEmploymentEndDate());
        if (req.getActive() != null)               employee.setActive(req.getActive());
        // Routed to the PERIOD, not the column. employees.norm_grace_days is a
        // trigger-maintained mirror and ProbationPolicy reads the period's
        // generated probation_end_date — setting the column alone changed
        // nothing, so probation could not actually be edited at all.
        if (req.getNormGraceDays() != null) {
            employmentPeriodService.changeProbationDays(employee.getId(), req.getNormGraceDays())
                    .ifPresent(range -> employeeRowRecalculator.recalculate(
                            employee, range[0], range[1],
                            "Trajanje probnog perioda promenjeno na " + req.getNormGraceDays() + " dana"));
        }
        if (req.getNotes() != null)                employee.setNotes(req.getNotes());
        if (req.getEmail() != null)                employee.setEmail(req.getEmail());
        if (req.getMobilePhone() != null)          employee.setMobilePhone(req.getMobilePhone());
        if (req.getPreferredLocale() != null) {
            // Validated against the supported set rather than passed through: the
            // column has a CHECK constraint, and a raw constraint violation is a
            // 500 the user cannot act on.
            //
            // isSupported rather than SUPPORTED.contains: the latter is
            // case-sensitive, so "SR-LATN" was refused here while normalize()
            // accepted it everywhere else — two different notions of "supported"
            // in one application. What is stored is normalize()'s canonical
            // spelling, so the column cannot collect casing variants.
            if (!AppLocales.isSupported(req.getPreferredLocale())) {
                throw new IllegalArgumentException(
                        "Nepodržan jezik: " + req.getPreferredLocale()
                                + ". Dozvoljeni su: " + String.join(", ", AppLocales.SUPPORTED) + ".");
            }
            employee.setPreferredLocale(AppLocales.normalize(req.getPreferredLocale()));
        }

        if (req.getHourlyRate() != null) {
            employee.setHourlyRate(req.getHourlyRate());
        }

        // NOT written here any more. Since 2026-09-22-01 the column is a
        // trigger-maintained mirror of employee_work_category_periods, so setting
        // it directly would be overwritten by the next period change and disagree
        // with the history until then. Use POST /employees/{id}/work-category-history.
        if (req.getDefaultWorkCategoryId() != null) {
            throw new IllegalArgumentException(
                    "Podrazumevana kategorija rada se menja preko istorije kategorija, "
                            + "sa datumom od kada važi.");
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

        openKartonOnReturnToWork(employee, wasInactive);

        return employeeRowEnricher.enrich(repository.findEmployeeWithBonusById(id))
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + id));
    }

    /**
     * A karton for THIS month when somebody is put back on the payroll.
     *
     * <p>Beside the one opened at creation, and for a different month on purpose:
     * a returning employee starts working NOW, not on the date they were first
     * hired, and it is this month they have to appear in. Employment start is the
     * right answer only the first time.
     *
     * <p>Only on the TRANSITION. Every save of the employee screen sends the whole
     * form, so an already-active employee is "set active" on each one — reacting
     * to the value rather than to the change would reopen a karton in whatever
     * month somebody last corrected a phone number.
     *
     * <p>No {@code PayrollMonthInitEvent}, for the same reason as at creation:
     * one person moving does not start a whole month's payroll.
     */
    private void openKartonOnReturnToWork(Employee employee, boolean wasInactive) {
        if (!wasInactive || !employee.isActive()) {
            return;
        }
        employeeRecordService.getOrCreateMonthlyRecord(employee.getId(), LocalDate.now());
    }
}
