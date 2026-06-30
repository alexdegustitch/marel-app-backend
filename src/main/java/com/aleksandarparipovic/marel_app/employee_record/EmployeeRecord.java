package com.aleksandarparipovic.marel_app.employee_record;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "employee_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_employee_records_employee_start_date",
                columnNames = {"employee_id", "start_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @PrePersist
    @PreUpdate
    void normalizeMonthWindow() {
        if (startDate == null) {
            return;
        }
        YearMonth yearMonth = YearMonth.from(startDate);
        this.startDate = yearMonth.atDay(1);
        this.endDate = yearMonth.atEndOfMonth();
    }
}


