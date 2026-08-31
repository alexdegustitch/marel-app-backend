package com.aleksandarparipovic.marel_app.overtime_record;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceCategoryCodes;
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
     * The day's covered minutes, with any neradni dan taken back out.
     *
     * <p>{@code total_shift_minutes} is the union of the shift's work-log
     * intervals, which is the measure of "how long were they here". The ND log
     * covers a shift nobody worked, so it is subtracted — see
     * {@code OvertimeRecordService} for why leaving it in lets the bank refill
     * itself.
     *
     * <p>The ND minutes are gathered in a grouped sub-select rather than a plain
     * join, so a report with several category rows cannot multiply the outer sum.
     * The category is matched by CODE, since every version of it keeps the code.
     */
    public int workedMinutesExcludingNonWorkingDay(Long employeeId, LocalDate workDate) {
        Integer minutes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(dr.total_shift_minutes), 0)
                     - COALESCE(SUM(nd.nd_minutes), 0)
                FROM daily_reports dr
                LEFT JOIN (
                    SELECT drc.daily_report_id, SUM(drc.total_minutes) AS nd_minutes
                    FROM daily_report_categories drc
                    JOIN work_code_categories c ON c.id = drc.work_code_category_id
                    WHERE c.category_no = :ndCode
                    GROUP BY drc.daily_report_id
                ) nd ON nd.daily_report_id = dr.id
                WHERE dr.employee_id = :employeeId
                  AND dr.work_date = :workDate
                  AND dr.archived_at IS NULL
                """,
                new MapSqlParameterSource("employeeId", employeeId)
                        .addValue("workDate", workDate)
                        .addValue("ndCode", AbsenceCategoryCodes.NON_WORKING_DAY),
                Integer.class);
        return minutes == null ? 0 : minutes;
    }
}
