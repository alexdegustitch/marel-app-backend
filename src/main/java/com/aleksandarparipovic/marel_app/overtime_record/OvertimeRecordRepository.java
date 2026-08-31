package com.aleksandarparipovic.marel_app.overtime_record;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OvertimeRecordRepository extends JpaRepository<OvertimeRecord, Long> {

    Optional<OvertimeRecord> findByEmployee_IdAndWorkDate(Long employeeId, LocalDate workDate);

    /**
     * The month's bank, oldest first — which is also the order it is spent in.
     *
     * <p>Ordered by id after the date so two rows can never come back in a
     * different order between runs. The allocation must produce the same answer
     * for the same data, or an unchanged month would rewrite itself on every
     * recalculation.
     */
    @Query("""
        select o from OvertimeRecord o
        where o.employee.id = :employeeId
          and o.workDate between :from and :to
        order by o.workDate asc, o.id asc
        """)
    List<OvertimeRecord> findForEmployeeBetween(@Param("employeeId") Long employeeId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
