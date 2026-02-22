package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeEditRequest;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
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

    @Column(name = "notes")
    private String notes;

    @Column(name = "is_foreigner", nullable = false)
    private boolean foreigner;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "norm_grace_days", nullable = false)
    private Integer normGraceDays = 30;

    @Column(name = "probation_end_date", insertable = false, updatable = false)
    private LocalDate probationEndDate;

    @Column(name = "transport_allowance_rsd", precision = 10, scale = 2)
    private BigDecimal transportAllowanceRsd;

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

        if (!Objects.equals(this.notes, request.getNotes())) {
            this.notes = request.getNotes();
        }

        if (!Objects.equals(this.employmentStartDate, request.getEmploymentStartDate())) {
            this.employmentStartDate = request.getEmploymentStartDate();
        }
    }


}
