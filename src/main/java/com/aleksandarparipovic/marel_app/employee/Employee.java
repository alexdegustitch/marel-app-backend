package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
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

    @Column(name = "full_name", nullable = false)
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
     * A personnel attribute, not a payroll rule and not a language.
     *
     * <p>Nothing in the calculation path reads this. Which categories an employee
     * may use and what coefficient applies comes from their compensation scheme
     * (see {@code employee_compensation_scheme_history}); which language their
     * documents are in comes from {@link #preferredLocale}. It was used once, by
     * the {@code 2026-07-27-02} migration, to seed the initial scheme periods.
     */
    @Column(name = "is_foreigner", nullable = false)
    private boolean foreigner;

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

    @Column(name = "works_in_commercial", nullable = false)
    private boolean worksInCommercial = false;

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

        if (!Objects.equals(this.fullName, request.getFullName())) {
            this.fullName = request.getFullName();
        }

        if (!Objects.equals(this.foreigner, request.getForeigner())) {
            this.foreigner = request.getForeigner();
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

        if (!Objects.equals(this.worksInCommercial, request.getWorksInCommercial())) {
            this.worksInCommercial = request.getWorksInCommercial() != null ? request.getWorksInCommercial() : this.worksInCommercial;
        }
    }


}
