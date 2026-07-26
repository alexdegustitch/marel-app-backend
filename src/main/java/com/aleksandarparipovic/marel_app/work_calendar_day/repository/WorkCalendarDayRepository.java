package com.aleksandarparipovic.marel_app.work_calendar_day.repository;

import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkCalendarDayRepository extends JpaRepository<WorkCalendarDay, Long> {

    List<WorkCalendarDay> findByCalendarDateBetweenOrderByCalendarDateAsc(LocalDate from, LocalDate to);

    Optional<WorkCalendarDay> findByCalendarDate(LocalDate calendarDate);

    boolean existsByCalendarDateBetween(LocalDate from, LocalDate to);
}
