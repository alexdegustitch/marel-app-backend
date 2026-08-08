package com.aleksandarparipovic.marel_app.employment_period;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeEmploymentPeriodRepository
        extends JpaRepository<EmployeeEmploymentPeriod, Long> {

    /**
     * The period covering this date, if any.
     *
     * <p>{@code ex_eep_no_overlap} guarantees at most one, so this cannot be
     * ambiguous — but the ordering is fixed anyway rather than relying on the
     * constraint to also decide which row comes back.
     */
    @Query("""
            SELECT p FROM EmployeeEmploymentPeriod p
            WHERE p.employee.id = :employeeId
              AND p.archivedAt IS NULL
              AND p.startedOn <= :date
              AND (p.endedOn IS NULL OR p.endedOn >= :date)
            ORDER BY p.startedOn DESC
            """)
    List<EmployeeEmploymentPeriod> findCovering(@Param("employeeId") Long employeeId,
                                                @Param("date") LocalDate date);

    /** The current spell — the latest by start date, which is what "date of employment" means. */
    @Query("""
            SELECT p FROM EmployeeEmploymentPeriod p
            WHERE p.employee.id = :employeeId AND p.archivedAt IS NULL
            ORDER BY p.startedOn DESC, p.id DESC
            """)
    List<EmployeeEmploymentPeriod> findLatest(@Param("employeeId") Long employeeId);

    default Optional<EmployeeEmploymentPeriod> findCoveringOne(Long employeeId, LocalDate date) {
        return findCovering(employeeId, date).stream().findFirst();
    }

    default Optional<EmployeeEmploymentPeriod> findLatestOne(Long employeeId) {
        return findLatest(employeeId).stream().findFirst();
    }
}
