package com.aleksandarparipovic.marel_app.recalc_queue;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-runs the months a dated change actually touched.
 *
 * <p>Every date-effective employee value — bonus category, hourly rate,
 * transport, telephone — prices work that may already have been calculated.
 * Changing one from a past date silently leaves the old numbers on the payslip
 * unless the affected months are put back through the calculator.
 *
 * <p>TWO RULES, BOTH THE OWNER'S:
 *
 * <ol>
 *   <li>A LOCKED payroll is never recalculated. It has been paid; recomputing it
 *       would move a number somebody has already acted on.</li>
 *   <li>The change is ALWAYS accepted, even when every affected month is locked
 *       and nothing can be recalculated at all. The caller reports which months
 *       were skipped rather than refusing the edit.</li>
 * </ol>
 *
 * <p>That second rule is why this returns a result instead of throwing: the
 * decision about the employee's data and the consequence for past payroll are
 * separate, and only the second one can be partially impossible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AffectedMonthsRecalculator {

    private final RecalcQueueService recalcQueueService;
    private final com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository payrollRunItemRepository;

    @PersistenceContext
    private EntityManager em;

    /**
     * What a change did — which months were queued, and which were left alone.
     *
     * @param recalculated months put back through the calculator
     * @param skippedLocked months left untouched because their payroll is locked
     */
    public record Result(List<YearMonth> recalculated, List<YearMonth> skippedLocked) {

        public boolean hasSkipped() {
            return !skippedLocked.isEmpty();
        }

        /** A sentence for the screen; empty when there is nothing worth saying. */
        public String messageOrEmpty() {
            if (skippedLocked.isEmpty() && recalculated.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!recalculated.isEmpty()) {
                sb.append("Pokrenuta je rekalkulacija za: ").append(format(recalculated)).append(".");
            }
            if (!skippedLocked.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("Zaključani obračuni nisu menjani: ").append(format(skippedLocked)).append(".");
            }
            return sb.toString();
        }

        private static String format(List<YearMonth> months) {
            return months.stream().map(m -> m.getMonthValue() + "/" + m.getYear()).reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    /**
     * Queue every month between {@code from} and {@code to} that this employee
     * has payroll for.
     *
     * @param to null means "open ended" — everything from {@code from} onwards,
     *           which is the shape of an ordinary change with no end date.
     */
    @Transactional
    public Result recalculate(Employee employee, LocalDate from, LocalDate to, String reason) {
        List<YearMonth> recalculated = new ArrayList<>();
        List<YearMonth> skipped = new ArrayList<>();

        for (Object[] row : monthsWithPayroll(employee.getId(), from, to)) {
            YearMonth month = YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            boolean locked = ((Number) row[2]).intValue() > 0;

            if (locked) {
                skipped.add(month);
                continue;
            }
            recalcQueueService.enqueueMonthlyJob(employee, month.getYear(), month.getMonthValue(), reason);

            /*
             * AND TELL THE PAYROLL ITSELF, not only the monthly report.
             *
             * The queued job rebuilds the REPORT. A payroll item re-prices when
             * it next notices it is stale, and until now the only thing that made
             * it notice was the report's version moving. A change that leaves the
             * report identical — a rate, an entitlement, anything that prices work
             * rather than measures it — need not move that version at all, and
             * then the payroll went on showing the old figure indefinitely.
             *
             * Flagged rather than recalculated here: the item re-prices on the
             * next read, which every list already does, and doing it inside the
             * caller's transaction would make saving a rate wait on every month
             * the employee has.
             */
            int marked = payrollRunItemRepository.markNeedsRecalculationByEmployeeAndMonth(
                    employee.getId(), month.getYear(), month.getMonthValue());
            if (marked > 0) {
                log.debug("Employee {}: {} payroll item(s) for {} flagged for repricing",
                        employee.getId(), marked, month);
            }

            recalculated.add(month);
        }

        if (!skipped.isEmpty()) {
            log.info("Employee {}: {} month(s) left alone because their payroll is locked — {}",
                    employee.getId(), skipped.size(), skipped);
        }
        return new Result(recalculated, skipped);
    }

    /**
     * The months this employee has a payroll item for in the range, each with a
     * count of LOCKED items.
     *
     * <p>By the item's own period rather than the run's, because that is what
     * ties an employee to a month. A month is treated as locked when ANY of its
     * items for this employee is locked — there is no half-locked month to
     * partially recalculate.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> monthsWithPayroll(Long employeeId, LocalDate from, LocalDate to) {
        return em.createNativeQuery("""
                SELECT EXTRACT(YEAR FROM pri.period)::int  AS y,
                       EXTRACT(MONTH FROM pri.period)::int AS m,
                       count(*) FILTER (WHERE pri.locked_at IS NOT NULL OR pri.status = 'LOCKED') AS locked
                FROM payroll_run_items pri
                WHERE pri.employee_id = :employeeId
                  AND pri.period >= date_trunc('month', CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL
                       OR pri.period <= date_trunc('month', CAST(:toDate AS date)))
                GROUP BY 1, 2
                ORDER BY 1, 2
                """)
                .setParameter("employeeId", employeeId)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();
    }
}
