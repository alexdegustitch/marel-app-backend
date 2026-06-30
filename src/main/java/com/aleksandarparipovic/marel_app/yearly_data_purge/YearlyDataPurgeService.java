package com.aleksandarparipovic.marel_app.yearly_data_purge;

import com.aleksandarparipovic.marel_app.yearly_data_purge.dto.YearlyDataPurgeResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class YearlyDataPurgeService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public YearlyDataPurgeResultDto preview(int year) {
        validateYear(year);
        prepareScope(year);
        return collectCounts(year, false);
    }

    @Transactional
    public YearlyDataPurgeResultDto execute(int year) {
        validateYear(year);
        prepareScope(year);

        long employeePayrollRunItemUpdates = deleteBySql("DELETE FROM employee_payroll_run_item_updates WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)");
        long employeeRecordUpdates = deleteBySql("DELETE FROM employee_record_updates WHERE employee_record_id IN (SELECT id FROM purge_employee_record_ids)");
        long payrollAdjustments = deleteBySql("DELETE FROM payroll_adjustments WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)");
        long payrollRunItemCategories = deleteBySql("DELETE FROM payroll_run_item_categories WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)");
        long payrollRunItems = deleteBySql("DELETE FROM payroll_run_items WHERE id IN (SELECT id FROM purge_payroll_run_item_ids)");
        long payrollRuns = deleteBySql("DELETE FROM payroll_runs WHERE id IN (SELECT id FROM purge_payroll_run_ids)");
        long monthlyReportCategories = deleteBySql("DELETE FROM monthly_report_categories WHERE monthly_report_id IN (SELECT id FROM purge_monthly_report_ids)");
        long monthlyReportRecalcQueue = deleteBySql("DELETE FROM monthly_report_recalc_queue WHERE report_year = (SELECT target_year FROM purge_scope)");
        long monthlyReports = deleteBySql("DELETE FROM monthly_reports WHERE id IN (SELECT id FROM purge_monthly_report_ids)");
        long dailyReportCategories = deleteBySql("DELETE FROM daily_report_categories WHERE daily_report_id IN (SELECT id FROM purge_daily_report_ids)");
        long dailyReportRecalcQueue = deleteBySql("DELETE FROM daily_report_recalc_queue WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids)");
        long dailyReports = deleteBySql("DELETE FROM daily_reports WHERE id IN (SELECT id FROM purge_daily_report_ids)");
        long workLogs = deleteBySql("DELETE FROM work_logs WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids)");
        long workShifts = deleteBySql("DELETE FROM work_shifts WHERE id IN (SELECT id FROM purge_work_shift_ids)");
        long employeeRecords = deleteBySql("DELETE FROM employee_records WHERE id IN (SELECT id FROM purge_employee_record_ids)");

        return YearlyDataPurgeResultDto.builder()
                .year(year)
                .executed(true)
                .employeePayrollRunItemUpdates(employeePayrollRunItemUpdates)
                .employeeRecordUpdates(employeeRecordUpdates)
                .employeeRecords(employeeRecords)
                .payrollAdjustments(payrollAdjustments)
                .payrollRunItemCategories(payrollRunItemCategories)
                .payrollRunItems(payrollRunItems)
                .payrollRuns(payrollRuns)
                .monthlyReportCategories(monthlyReportCategories)
                .monthlyReportRecalcQueue(monthlyReportRecalcQueue)
                .monthlyReports(monthlyReports)
                .dailyReportCategories(dailyReportCategories)
                .dailyReportRecalcQueue(dailyReportRecalcQueue)
                .dailyReports(dailyReports)
                .workLogs(workLogs)
                .workShifts(workShifts)
                .build();
    }

    private void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("Year must be between 2000 and 2100");
        }
    }

    private void prepareScope(int year) {
        int nextYear = year + 1;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("year", year)
                .addValue("nextYear", nextYear);

        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_scope");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_work_shift_ids");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_daily_report_ids");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_monthly_report_ids");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_payroll_run_ids");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_payroll_run_item_ids");
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS purge_employee_record_ids");

        jdbcTemplate.update("""
                CREATE TEMP TABLE purge_scope ON COMMIT DROP AS
                SELECT
                    :year::int AS target_year,
                    make_date(:year, 1, 1) AS start_date,
                    make_date(:nextYear, 1, 1) AS next_year_date
                """, params);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_work_shift_ids ON COMMIT DROP AS
                SELECT ws.id
                FROM work_shifts ws
                JOIN purge_scope s
                  ON ws.work_date >= s.start_date
                 AND ws.work_date < s.next_year_date
                """);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_daily_report_ids ON COMMIT DROP AS
                SELECT dr.id
                FROM daily_reports dr
                JOIN purge_work_shift_ids pws ON pws.id = dr.work_shift_id
                """);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_monthly_report_ids ON COMMIT DROP AS
                SELECT mr.id
                FROM monthly_reports mr
                JOIN purge_scope s
                  ON mr.start_date >= s.start_date
                 AND mr.start_date < s.next_year_date
                """);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_employee_record_ids ON COMMIT DROP AS
                SELECT er.id
                FROM employee_records er
                JOIN purge_scope s
                  ON er.start_date >= s.start_date
                 AND er.start_date < s.next_year_date
                """);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_payroll_run_ids ON COMMIT DROP AS
                SELECT pr.id
                FROM payroll_runs pr
                JOIN purge_scope s ON pr.report_year = s.target_year
                """);

        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TEMP TABLE purge_payroll_run_item_ids ON COMMIT DROP AS
                SELECT DISTINCT pri.id
                FROM payroll_run_items pri
                LEFT JOIN purge_payroll_run_ids ppr ON ppr.id = pri.payroll_run_id
                LEFT JOIN purge_monthly_report_ids pmr ON pmr.id = pri.monthly_report_id
                WHERE ppr.id IS NOT NULL OR pmr.id IS NOT NULL
                """);
    }

    private YearlyDataPurgeResultDto collectCounts(int year, boolean executed) {
        return YearlyDataPurgeResultDto.builder()
                .year(year)
                .executed(executed)
                .employeePayrollRunItemUpdates(count("SELECT COUNT(*) FROM employee_payroll_run_item_updates WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)"))
                .employeeRecordUpdates(count("SELECT COUNT(*) FROM employee_record_updates WHERE employee_record_id IN (SELECT id FROM purge_employee_record_ids)"))
                .employeeRecords(count("SELECT COUNT(*) FROM employee_records WHERE id IN (SELECT id FROM purge_employee_record_ids)"))
                .payrollAdjustments(count("SELECT COUNT(*) FROM payroll_adjustments WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)"))
                .payrollRunItemCategories(count("SELECT COUNT(*) FROM payroll_run_item_categories WHERE payroll_run_item_id IN (SELECT id FROM purge_payroll_run_item_ids)"))
                .payrollRunItems(count("SELECT COUNT(*) FROM payroll_run_items WHERE id IN (SELECT id FROM purge_payroll_run_item_ids)"))
                .payrollRuns(count("SELECT COUNT(*) FROM payroll_runs WHERE id IN (SELECT id FROM purge_payroll_run_ids)"))
                .monthlyReportCategories(count("SELECT COUNT(*) FROM monthly_report_categories WHERE monthly_report_id IN (SELECT id FROM purge_monthly_report_ids)"))
                .monthlyReportRecalcQueue(count("SELECT COUNT(*) FROM monthly_report_recalc_queue WHERE report_year = (SELECT target_year FROM purge_scope)"))
                .monthlyReports(count("SELECT COUNT(*) FROM monthly_reports WHERE id IN (SELECT id FROM purge_monthly_report_ids)"))
                .dailyReportCategories(count("SELECT COUNT(*) FROM daily_report_categories WHERE daily_report_id IN (SELECT id FROM purge_daily_report_ids)"))
                .dailyReportRecalcQueue(count("SELECT COUNT(*) FROM daily_report_recalc_queue WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids)"))
                .dailyReports(count("SELECT COUNT(*) FROM daily_reports WHERE id IN (SELECT id FROM purge_daily_report_ids)"))
                .workLogs(count("SELECT COUNT(*) FROM work_logs WHERE work_shift_id IN (SELECT id FROM purge_work_shift_ids)"))
                .workShifts(count("SELECT COUNT(*) FROM work_shifts WHERE id IN (SELECT id FROM purge_work_shift_ids)"))
                .build();
    }

    private long count(String sql) {
        Long result = jdbcTemplate.getJdbcTemplate().queryForObject(sql, Long.class);
        return result == null ? 0L : result;
    }

    private long deleteBySql(String sql) {
        return jdbcTemplate.getJdbcTemplate().update(sql);
    }
}


