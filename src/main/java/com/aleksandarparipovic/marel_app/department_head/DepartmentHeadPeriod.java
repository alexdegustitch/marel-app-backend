package com.aleksandarparipovic.marel_app.department_head;

import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.shift.Shift;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One spell of somebody heading a department.
 *
 * <p>A period rather than a column on {@code departments}, because "who was head
 * of this department in March" is a question an old report raises. See
 * {@code 2026-09-19-02}.
 *
 * <p>{@link #shift} is optional and the two cases differ: null means head across
 * all shifts, a value means that shift only. Both may be in force at once — the
 * {@code ex_dhp_no_overlap} exclusion constraint folds null to -1, so it refuses
 * two department-wide heads and two heads of the same shift, while allowing a
 * general head with shift leads under them.
 */
@Entity
@Table(name = "department_head_periods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentHeadPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** Null = head across all shifts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive, like every other period in this schema. Null = still in post. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Maintained by trg_03_dhp_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isOpen() {
        return validTo == null;
    }
}
