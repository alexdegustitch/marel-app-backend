package com.aleksandarparipovic.marel_app.overtime_record;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * How long an employee actually worked on one day.
 *
 * <p>Summed across every shift of that day rather than per shift, because
 * overtime is a property of the day: eight hours in the first shift and eight in
 * the third is eight hours of overtime, and neither shift on its own says so.
 */
@Repository
@RequiredArgsConstructor
public class OvertimeQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The day's covered minutes, summed over every shift of that day.
     *
     * <p>{@code total_shift_minutes} is the union of a shift's work-log intervals
     * — how long the employee was actually here. It ALREADY excludes any neradni
     * dan: {@code DailyRecalcService} drops ND logs before the report is built,
     * so an excused day contributes nothing here and the bank cannot refill
     * itself out of the ND it just paid for.
     */
    public int workedMinutesExcludingNonWorkingDay(Long employeeId, LocalDate workDate) {
        Integer minutes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(dr.total_shift_minutes), 0)
                FROM daily_reports dr
                WHERE dr.employee_id = :employeeId
                  AND dr.work_date = :workDate
                  AND dr.archived_at IS NULL
                """,
                new MapSqlParameterSource("employeeId", employeeId)
                        .addValue("workDate", workDate),
                Integer.class);
        return minutes == null ? 0 : minutes;
    }
}
