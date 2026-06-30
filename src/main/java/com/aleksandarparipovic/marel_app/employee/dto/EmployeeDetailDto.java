package com.aleksandarparipovic.marel_app.employee.dto;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

@Getter
public class EmployeeDetailDto {

    private final Long id;
    private final String employeeNo;
    private final String fullName;

    private final Long departmentId;
    private final String departmentName;

    private final LocalDate employmentStartDate;
    private final LocalDate employmentEndDate;
    private final LocalDate probationEndDate;
    private final boolean active;
    private final boolean foreigner;
    private final Integer normGraceDays;

    private final BigDecimal transportAllowanceRsd;
    private final String transportAllowanceMode;

    private final Long categoryId;
    private final String categoryNo;
    private final String categoryName;
    private final BigDecimal bonusAmount;
    private final LocalDate bonusStart;

    private final String mobilePhone;
    private final BigDecimal hourlyRate;
    private final Long defaultWorkCategoryId;
    private final String defaultWorkCategoryName;
    private final String defaultWorkCategoryNo;
    private final boolean worksInCommercial;

    private final String notes;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    private final List<BonusHistoryEntry> bonusHistory;

    @Getter
    public static class BonusHistoryEntry {
        private final Long bonusId;
        private final Long bonusCategoryId;
        private final String bonusCategoryNo;
        private final String bonusCategoryName;
        private final BigDecimal bonusAmount;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final boolean active;
        private final OffsetDateTime createdAt;

        public BonusHistoryEntry(EmployeeBonus b) {
            this.bonusId = b.getId();
            this.bonusCategoryId = b.getBonusCategory() != null ? b.getBonusCategory().getId() : null;
            this.bonusCategoryNo = b.getBonusCategory() != null ? b.getBonusCategory().getCategoryNo() : null;
            this.bonusCategoryName = b.getBonusCategory() != null ? b.getBonusCategory().getCategoryName() : null;
            this.bonusAmount = b.getBonusCategory() != null ? b.getBonusCategory().getBonusAmount() : null;
            this.startDate = b.getStartDate();
            this.endDate = b.getEndDate();
            this.active = b.isActive();
            this.createdAt = b.getCreatedAt();
        }
    }

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
        this.transportAllowanceMode = e.getTransportAllowanceMode();

        EmployeeBonus activeBonus = e.getEmployeeBonuses() == null ? null :
                e.getEmployeeBonuses().stream()
                        .filter(b -> b.getEndDate() == null)
                        .max(Comparator.comparing(EmployeeBonus::getStartDate))
                        .orElse(null);

        this.categoryId   = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getId() : null;
        this.categoryNo   = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getCategoryNo() : null;
        this.categoryName = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getCategoryName() : null;
        this.bonusAmount  = activeBonus != null && activeBonus.getBonusCategory() != null ? activeBonus.getBonusCategory().getBonusAmount() : null;
        this.bonusStart   = activeBonus != null ? activeBonus.getStartDate() : null;

        this.mobilePhone = e.getMobilePhone();
        this.hourlyRate = e.getHourlyRate();
        this.defaultWorkCategoryId = e.getDefaultWorkCategory() != null ? e.getDefaultWorkCategory().getId() : null;
        this.defaultWorkCategoryName = e.getDefaultWorkCategory() != null ? e.getDefaultWorkCategory().getCategoryName() : null;
        this.defaultWorkCategoryNo = e.getDefaultWorkCategory() != null ? e.getDefaultWorkCategory().getCategoryNo() : null;
        this.worksInCommercial = e.isWorksInCommercial();

        this.notes = e.getNotes();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
        this.archivedAt = e.getArchivedAt();

        this.bonusHistory = e.getEmployeeBonuses() == null ? List.of() :
                e.getEmployeeBonuses().stream()
                        .sorted(Comparator
                                .<EmployeeBonus, Boolean>comparing(b -> b.getEndDate() != null) // active (null endDate) first
                                .thenComparing(Comparator.comparing(EmployeeBonus::getStartDate).reversed())
                                .thenComparing(Comparator.comparing(
                                        b -> b.getEndDate() != null ? b.getEndDate() : LocalDate.MAX,
                                        Comparator.reverseOrder())))
                        .map(BonusHistoryEntry::new)
                        .toList();

    }
}
