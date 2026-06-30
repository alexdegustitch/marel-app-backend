package com.aleksandarparipovic.marel_app.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long> {
    List<Shift> findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc();
    Optional<Shift> findFirstByShiftCodeAndIsActiveTrue(String shiftCode);
}
