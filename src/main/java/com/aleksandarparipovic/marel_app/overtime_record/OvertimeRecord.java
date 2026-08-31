package com.aleksandarparipovic.marel_app.overtime_record;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The minutes an employee worked beyond a regular day, on one DAY.
 *
 * <p><b>A day, not a shift.</b> Somebody who works eight hours in the first
 * shift and eight more in the third has worked eight hours of overtime, though
 * neither shift on its own is longer than a regular one. Keyed by shift, that
 * day reads as two ordinary shifts and the overtime is lost.
 *
 * <p>Derived, never entered: {@code OvertimeRecordService} writes, updates and
 * removes these rows from the daily recalculation. A day with no overtime has no
 * row at all — the absence of a row is the zero, which is why
 * {@code chk_overtime_records_minutes_positive} refuses one.
 *
 * <p>What the row is FOR is {@code absence_compensations}: it is the thing an
 * unpaid absence can be paid for out of, and the reason a covered full-shift
 * absence can become a neradni dan (ND) instead of an unpaid one (NO).
 */
@Entity
@Table(
        name = "overtime_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_overtime_records_employee_day",
                columnNames = {"employee_id", "work_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OvertimeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** Strictly positive. See the class note on why zero has no row. */
    @Column(name = "overtime_minutes", nullable = false)
    private Integer overtimeMinutes;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
