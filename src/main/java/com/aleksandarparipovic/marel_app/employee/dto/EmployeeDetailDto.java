package com.aleksandarparipovic.marel_app.employee.dto;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
public class EmployeeDetailDto {

    // ── Identity ─────────────────────────────────────────────────────────────
    private final Long id;
    private final String employeeNo;
    private final String fullName;

    // ── Department ───────────────────────────────────────────────────────────
    private final Long departmentId;
    private final String departmentName;

    // ── Employment ───────────────────────────────────────────────────────────
    private final LocalDate employmentStartDate;
    private final LocalDate employmentEndDate;
    private final LocalDate probationEndDate;
    private final boolean active;
    private final boolean foreigner;
    private final Integer normGraceDays;

    // ── Financials ───────────────────────────────────────────────────────────
    private final BigDecimal transportAllowanceRsd;

    // ── Bonus ────────────────────────────────────────────────────────────────
    private final Long categoryId;
    private final String categoryNo;
    private final String categoryName;
    private final BigDecimal bonusAmount;
    private final LocalDate bonusStart;

    // ── Meta ─────────────────────────────────────────────────────────────────
    private final String notes;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public EmployeeDetailDto(Employee e) {
        this.id = e.getId();
        this.employeeNo = e.getEmployeeNo();
        this.fullName = e.getFullName();

        this.departmentId = e.getDepartment() != null ? e.getDepartment().getId() : null;
        this.departmentName = e.getDepartment() != null ? e.getDepartment().getName() : null;

        this.employmentStartDate = e.getEmploymentStartDate();
        this.employmentEndDate = e.getEmploymentEndDate();
        this.probationEndDate = e.getProbationEndDate();
        this.active = e.isActive();
        this.foreigner = e.isForeigner();
        this.normGraceDays = e.getNormGraceDays();

        this.transportAllowanceRsd = e.getTransportAllowanceRsd();

        EmployeeBonus activeBonus = e.getEmployeeBonuses() == null ? null :
                e.getEmployeeBonuses().stream()
                        .filter(b -> b.getEndDate() == null)
                        .max(java.util.Comparator.comparing(EmployeeBonus::getStartDate))
                        .orElse(null);

        this.categoryId    = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getId() : null;
        this.categoryNo    = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getCategoryNo() : null;
        this.categoryName  = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getName() : null;
        this.bonusAmount   = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getBonusAmount() : null;
        this.bonusStart    = activeBonus != null ? activeBonus.getStartDate() : null;

        this.notes = e.getNotes();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
        this.archivedAt = e.getArchivedAt();
    }
}

