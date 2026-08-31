package com.aleksandarparipovic.marel_app.absence_compensation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AbsenceCompensationRepository extends JpaRepository<AbsenceCompensation, Long> {

    /**
     * Every row explaining a set of absences, oldest overtime first.
     *
     * <p>The order is what the screen prints: "two hours from the 12th, one from
     * the 10th" has to come back the same way twice.
     */
    @Query("""
        select c from AbsenceCompensation c
        join fetch c.overtimeRecord o
        where c.absenceRecord.id in :absenceRecordIds
        order by c.absenceRecord.id asc, o.workDate asc, o.id asc
        """)
    List<AbsenceCompensation> findForAbsences(@Param("absenceRecordIds") Collection<Long> absenceRecordIds);

    /**
     * Clears the allocation for a set of absences so it can be rebuilt.
     *
     * <p>A hard delete, deliberately: a row here is the result of a calculation,
     * not a thing that happened, and soft-deleting would leave one dead row per
     * absence per recalculation behind.
     */
    // flushAutomatically so pending writes land before the delete; NOT
    // clearAutomatically — the caller is still holding the AbsenceRecord entities
    // it is about to write verdicts onto, and clearing would detach them mid-flight.
    @Modifying(flushAutomatically = true)
    @Query("delete from AbsenceCompensation c where c.absenceRecord.id in :absenceRecordIds")
    void deleteForAbsences(@Param("absenceRecordIds") Collection<Long> absenceRecordIds);
}
