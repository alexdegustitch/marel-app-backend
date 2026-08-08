package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department_head.DepartmentHeadPeriod;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @OneToMany(mappedBy = "employee", fetch = FetchType.LAZY)
    private Set<EmployeeBonus> employeeBonuses = new HashSet<>();

    @Column(name = "first_name", nullable = false, length = 120)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 120)
    private String lastName;

    /**
     * How this worker's name is rendered, everywhere.
     *
     * <p>DB-generated (GENERATED ALWAYS AS first_name || ' ' || last_name) and so
     * read-only here — the same arrangement as {@code User.fullName}. Roughly
     * forty report, search and ORDER BY queries across payroll, employee records,
     * work shifts and analytics read {@code employees.full_name}; deriving it in
     * the database is what stops those from disagreeing with the name parts.
     *
     * <p>{@code @Generated} makes Hibernate re-read the value after an insert or
     * update. Without it a freshly saved Employee has {@code fullName == null} for
     * the rest of the transaction, which is exactly the bug that bit User.
     */
    @org.hibernate.annotations.Generated(event = {
            org.hibernate.generator.EventType.INSERT,
            org.hibernate.generator.EventType.UPDATE
    })
    @Setter(AccessLevel.NONE)
    @Column(name = "full_name", insertable = false, updatable = false)
    private String fullName;

    @Column(name = "employee_no", nullable = false, unique = true)
    private String employeeNo;

    @Column(name = "employment_start_date", nullable = false)
    private LocalDate employmentStartDate;

    @Column(name = "employment_end_date")
    private LocalDate employmentEndDate;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    /**
     * Optional contact address. The only one of the three columns added by
     * {@code 2026-09-19-01} that this application shows or edits.
     */
    @Column(name = "email", length = 255)
    private String email;

    /**
     * Personal identification number, 13 digits.
     *
     * <p>Held so payroll can eventually produce a tax filing. Deliberately NOT
     * carried on any DTO: the employee list is visible to every administrator,
     * and who may read a JMBG is a decision nobody has made yet. Put it on a
     * screen only together with that decision.
     */
    @Column(name = "jmbg", length = 13)
    private String jmbg;

    /**
     * Account a salary is paid into. Same access reasoning as {@link #jmbg} —
     * stored, not exposed.
     */
    @Column(name = "bank_account", length = 34)
    private String bankAccount;

    /**
     * Every compensation-scheme period this employee has had.
     *
     * <p>Mapped so "what kind of worker is this" can be answered by a JOIN rather
     * than by a column. Since {@code 2026-09-19-03} there is no
     * {@code is_foreigner} or {@code works_in_commercial}: the scheme is the only
     * source, and the traits the screens show are derived from it.
     */
    @OneToMany(mappedBy = "employee", fetch = FetchType.LAZY)
    private Set<EmployeeCompensationSchemeHistory> compensationSchemePeriods = new HashSet<>();

    /**
     * Every spell this employee has spent heading a department.
     *
     * <p>Mapped so a correlated subquery can reach it through {@code correlate()}.
     * Comparing {@code head.get("employee")} to the outer root instead produced
     * "Could not locate TableGroup" at runtime — Hibernate 6 cannot resolve that
     * shape inside a constructor select.
     */
    @OneToMany(mappedBy = "employee", fetch = FetchType.LAZY)
    private Set<DepartmentHeadPeriod> departmentHeadPeriods = new HashSet<>();

    /**
     * Language for documents produced FOR this employee, currently the payroll
     * PDF.
     *
     * <p>Deliberately independent of {@link #foreigner} and of the compensation
     * scheme: "foreign employee therefore English" is not a rule this system
     * implements. The language is chosen explicitly. It never affects a
     * calculated amount.
     */
    @Column(name = "preferred_locale", nullable = false, length = 35)
    private String preferredLocale = com.aleksandarparipovic.marel_app.common.i18n.AppLocales.DEFAULT;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "norm_grace_days", nullable = false)
    private Integer normGraceDays = 30;

    @Column(name = "probation_end_date", insertable = false, updatable = false)
    private LocalDate probationEndDate;

    @Column(name = "transport_allowance_rsd", precision = 10, scale = 2)
    private BigDecimal transportAllowanceRsd;

    @Column(name = "transport_allowance_mode", nullable = false, length = 20)
    private String transportAllowanceMode = "AUTO";

    @Column(name = "mobile_phone", length = 50)
    private String mobilePhone;

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_work_category_id")
    private WorkCodeCategory defaultWorkCategory;


    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isCurrentlyEmployed() {
        LocalDate today = LocalDate.now();
        return !today.isBefore(employmentStartDate)
                && (employmentEndDate == null || !today.isAfter(employmentEndDate));
    }

    public void updateFrom(EmployeeEditRequest request) {

        if (!Objects.equals(this.employeeNo, request.getEmployeeNo())) {
            this.employeeNo = request.getEmployeeNo();
        }

        if (!Objects.equals(this.firstName, request.getFirstName())) {
            this.firstName = request.getFirstName();
        }

        if (!Objects.equals(this.lastName, request.getLastName())) {
            this.lastName = request.getLastName();
        }

        if (!Objects.equals(this.email, request.getEmail())) {
            this.email = request.getEmail();
        }

        if (!Objects.equals(this.transportAllowanceRsd, request.getTransportAllowanceRsd())) {
            this.transportAllowanceRsd = request.getTransportAllowanceRsd();
        }

        if (request.getTransportAllowanceMode() != null &&
                !Objects.equals(this.transportAllowanceMode, request.getTransportAllowanceMode())) {
            this.transportAllowanceMode = request.getTransportAllowanceMode();
        }

        if (!Objects.equals(this.notes, request.getNotes())) {
            this.notes = request.getNotes();
        }

        if (!Objects.equals(this.employmentStartDate, request.getEmploymentStartDate())) {
            this.employmentStartDate = request.getEmploymentStartDate();
        }

        if (!Objects.equals(this.mobilePhone, request.getMobilePhone())) {
            this.mobilePhone = request.getMobilePhone();
        }

        if (!Objects.equals(this.hourlyRate, request.getHourlyRate())) {
            this.hourlyRate = request.getHourlyRate();
        }

    }


}
