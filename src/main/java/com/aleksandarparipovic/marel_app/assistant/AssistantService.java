package com.aleksandarparipovic.marel_app.assistant;

import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository.EmployeeOperationPerf;
import com.aleksandarparipovic.marel_app.assistant.dto.AssistantTextResponse;
import com.aleksandarparipovic.marel_app.assistant.dto.EmployeeAnalysisRequest;
import com.aleksandarparipovic.marel_app.assistant.dto.EmployeeMonthReport;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository.ShiftTypeCountProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * "Spiky" — a short, read-only, Serbian summary of one employee's month.
 *
 * <p>Grounded ONLY in real figures read from the database. The model is asked to
 * phrase those figures, never to invent or estimate any. Nothing here writes.
 */
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final String NO_DATA_TEXT = "Nema podataka za tog radnika u tom mesecu.";

    private static final String SYSTEM_PROMPT = """
            Ti si Spiky, kratak i precizan pomoćnik u fabričkoj aplikaciji. \
            Odgovaraj isključivo na srpskom, 2–4 rečenice. \
            Koristi ISKLJUČIVO brojke koje su ti date — ništa ne izmišljaj i ne procenjuj. \
            Ako neki podatak nije dat, ne pominji ga.""";

    private final EmployeeRecordService employeeRecordService;
    private final MonthlyReportRepository monthlyReportRepository;
    private final AssistantQuotaService quotaService;
    private final OpenAiClient openAiClient;
    private final CurrentUserService currentUserService;
    private final AnalyticsQueryRepository analyticsQueryRepository;
    private final WorkShiftRepository workShiftRepository;

    /** A shift performing above this (%) is "good"; below the other, "weak". */
    private static final BigDecimal GOOD_SHIFT_THRESHOLD = BigDecimal.valueOf(100);
    private static final BigDecimal WEAK_SHIFT_THRESHOLD = BigDecimal.valueOf(90);
    /** How many best/worst operations the report carries. */
    private static final int TOP_N_OPERATIONS = 3;

    /**
     * The full flow. Assumes the caller (controller) has already checked that the
     * feature is enabled. Returns the plain "no data" text WITHOUT touching the
     * quota or the model; otherwise counts one call against the quota and asks
     * the model to phrase the facts.
     */
    public AssistantTextResponse analyze(EmployeeAnalysisRequest request) {
        return gatherFacts(request)
                .map(f -> new AssistantTextResponse(buildTemplateText(f)))
                .orElseGet(() -> new AssistantTextResponse(NO_DATA_TEXT));
    }

    /**
     * The karton's own entry point: the summary for one monthly record, looked
     * up by its id (the karton page knows the record, not the employee+month).
     */
    public AssistantTextResponse analyzeByRecordId(Long employeeRecordId) {
        return monthlyReportRepository
                .findByEmployeeRecordIdWithEmployeeAndDepartment(employeeRecordId)
                .map(report -> new AssistantTextResponse(buildTemplateText(toFacts(report))))
                .orElseGet(() -> new AssistantTextResponse(NO_DATA_TEXT));
    }

    /**
     * A STRUCTURED, read-only monthly report for one karton, built
     * DETERMINISTICALLY from real figures — no model call, nothing leaves the box.
     *
     * <p>The headline hours and totals come from the stored {@link MonthlyReport};
     * the per-shift and per-operation standings from the analytics fact table
     * (the same duration-weighted approved rate the reports use); the shift-type
     * distribution from the live shifts. Anything not reliably sourceable is left
     * null/empty rather than guessed — see {@link EmployeeMonthReport}.
     */
    @Transactional(readOnly = true)
    public EmployeeMonthReport buildEmployeeReport(Long employeeRecordId) {
        return monthlyReportRepository
                .findByEmployeeRecordIdWithEmployeeAndDepartment(employeeRecordId)
                .map(this::toEmployeeReport)
                .orElseGet(EmployeeMonthReport::empty);
    }

    private EmployeeMonthReport toEmployeeReport(MonthlyReport report) {
        Employee employee = report.getEmployeeRecord().getEmployee();
        String department = employee.getDepartment() != null ? employee.getDepartment().getName() : null;

        YearMonth ym = YearMonth.from(report.getEmployeeRecord().getStartDate());
        LocalDate monthStart = ym.atDay(1);

        YearMonth prev = ym.minusMonths(1);
        BigDecimal prevRate = employeeRecordService
                .findRecordIdForEmployeeAndMonth(employee.getId(), prev.getYear(), prev.getMonthValue())
                .flatMap(monthlyReportRepository::findByEmployeeRecord_Id)
                .map(this::effectiveRate)
                .orElse(null);

        // Per-shift performance → how many good / weak. Same weighted approved rate
        // the analytics pages use, so "a good shift" means the same thing here.
        List<BigDecimal> shiftRates =
                analyticsQueryRepository.findShiftPerformanceRatesForEmployeeMonth(employee.getId(), monthStart);
        int goodShifts = (int) shiftRates.stream()
                .filter(r -> r != null && r.compareTo(GOOD_SHIFT_THRESHOLD) > 0).count();
        int weakShifts = (int) shiftRates.stream()
                .filter(r -> r != null && r.compareTo(WEAK_SHIFT_THRESHOLD) < 0).count();

        // Shift-type distribution from the live shifts; their sum is the month's total shifts.
        List<EmployeeMonthReport.NameCount> shiftTypes = workShiftRepository
                .findShiftTypeCountsForEmployeeMonth(employee.getId(), monthStart, monthStart.plusMonths(1))
                .stream()
                .map(p -> new EmployeeMonthReport.NameCount(p.getName(), p.getShiftCount()))
                .toList();
        int totalShifts = shiftTypes.stream().mapToInt(EmployeeMonthReport.NameCount::count).sum();

        // Best / worst operations, ranked by the weighted rate over the month.
        List<EmployeeOperationPerf> operations =
                analyticsQueryRepository.findOperationPerformanceForEmployeeMonth(employee.getId(), monthStart);
        List<EmployeeMonthReport.OperationStat> ranked = operations.stream()
                .filter(op -> op.rate() != null)
                .map(op -> new EmployeeMonthReport.OperationStat(
                        op.operationId(), op.operationName(),
                        op.productId(), op.productName(), op.productCode(),
                        oneDecimalBd(op.rate()), (int) op.quantity()))
                .toList();
        List<EmployeeMonthReport.OperationStat> topOperations = ranked.stream()
                .limit(TOP_N_OPERATIONS).toList();
        List<EmployeeMonthReport.OperationStat> weakOperations = ranked.stream()
                .skip(Math.max(0, ranked.size() - TOP_N_OPERATIONS))
                .sorted((a, b) -> a.rate().compareTo(b.rate()))
                .toList();

        return new EmployeeMonthReport(
                true,
                employee.getFullName(),
                employee.getEmployeeNo(),
                department,
                ym.getMonthValue(),
                ym.getYear(),
                effectiveRate(report),
                prevRate,
                minutesToHoursBd(report.getTotalWorkMinutes()),
                minutesToHoursBd(report.getTotalShiftMinutes()),
                minutesToHoursBd(report.getTotalAbsencePaidMinutes()),
                minutesToHoursBd(report.getTotalAbsenceUnpaidMinutes()),
                minutesToHoursBd(report.getTotalSickLeaveMinutes()),
                report.getTotalQuantity() != null ? report.getTotalQuantity() : 0,
                report.getTotalScrap() != null ? report.getTotalScrap() : 0,
                totalShifts,
                goodShifts,
                weakShifts,
                null, // missingOrIncompleteShifts: no reliable existing signal — see DTO.
                topOperations,
                weakOperations,
                shiftTypes,
                List.of()); // operationTypes: no operation category/type is sourceable — see DTO.
    }

    /**
     * The rate to REPORT: the approved (capped) percentage when present, else the
     * raw one. The same fallback the text summary uses.
     */
    private BigDecimal effectiveRate(MonthlyReport report) {
        BigDecimal rate = report.getApprovedPerformanceRate() != null
                ? report.getApprovedPerformanceRate()
                : report.getPerformanceRate();
        return rate == null ? null : oneDecimalBd(rate);
    }

    private int dailyLimitFor(CustomUserDetails user) {
        boolean privileged = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equalsIgnoreCase("ROLE_admin") || a.equalsIgnoreCase("ROLE_supervisor"));
        return privileged
                ? AssistantQuotaService.PRIVILEGED_DAILY_LIMIT
                : AssistantQuotaService.DEFAULT_DAILY_LIMIT;
    }

    /**
     * Read the real numbers for (employee, year, month), or empty when there is
     * no karton or no monthly report for that month.
     *
     * <p>The employee and department are JOIN FETCH-ed so the returned
     * {@link Facts} is fully materialised — the model call (below) then runs with
     * no DB session held open, and no lazy reference can be touched later.
     */
    private Optional<Facts> gatherFacts(EmployeeAnalysisRequest request) {
        Optional<Long> recordId = employeeRecordService.findRecordIdForEmployeeAndMonth(
                request.employeeId(), request.year(), request.month());
        if (recordId.isEmpty()) {
            return Optional.empty();
        }

        return monthlyReportRepository
                .findByEmployeeRecordIdWithEmployeeAndDepartment(recordId.get())
                .map(report -> toFacts(report, request));
    }

    private Facts toFacts(MonthlyReport report, EmployeeAnalysisRequest request) {
        Employee employee = report.getEmployeeRecord().getEmployee();
        String departmentName = employee.getDepartment() != null ? employee.getDepartment().getName() : null;
        // approved_performance_rate is stored as a percentage (e.g. 105.0000 = 105 %),
        // capped; fall back to the raw performance_rate when no approved figure exists.
        BigDecimal rate = report.getApprovedPerformanceRate() != null
                ? report.getApprovedPerformanceRate()
                : report.getPerformanceRate();

        return new Facts(
                employee.getFullName(),
                employee.getEmployeeNo(),
                departmentName,
                request.month(),
                request.year(),
                rate,
                report.getTotalShiftMinutes(),
                report.getTotalWorkMinutes(),
                report.getTotalAbsencePaidMinutes(),
                report.getTotalAbsenceUnpaidMinutes(),
                report.getTotalSickLeaveMinutes(),
                report.getTotalQuantity(),
                report.getTotalScrap()
        );
    }

    /** Facts for a monthly report, taking month/year from the record's month. */
    private Facts toFacts(MonthlyReport report) {
        java.time.YearMonth ym = java.time.YearMonth.from(report.getEmployeeRecord().getStartDate());
        return toFacts(report, new EmployeeAnalysisRequest(
                report.getEmployeeRecord().getEmployee().getId(),
                ym.getMonthValue(),
                ym.getYear()));
    }

    private static final String[] SR_MONTHS = {
            "januar", "februar", "mart", "april", "maj", "jun",
            "jul", "avgust", "septembar", "oktobar", "novembar", "decembar"
    };

    /**
     * Spiky's voice — a short, natural Serbian summary built DETERMINISTICALLY
     * from the real figures (no model, nothing leaves the server). Reads as 2–4
     * sentences; a figure that is null or zero is simply left out so the text
     * never states "0 h bolovanja" as if it mattered.
     */
    private String buildTemplateText(Facts f) {
        String monthName = f.month() >= 1 && f.month() <= 12 ? SR_MONTHS[f.month() - 1] : String.valueOf(f.month());
        StringBuilder sb = new StringBuilder();

        // 1) Who, where, and the headline performance with a plain verdict.
        sb.append(f.name());
        if (f.departmentName() != null) sb.append(" (").append(f.departmentName()).append(")");
        sb.append(" — ").append(monthName).append(' ').append(f.year()).append(".");
        if (f.performanceRate() != null) {
            String rate = oneDecimal(f.performanceRate());
            int cmp = f.performanceRate().compareTo(BigDecimal.valueOf(100));
            String verdict = cmp >= 0 ? "iznad norme" : "ispod norme";
            sb.append(" Učinak ").append(rate).append("% (").append(verdict).append(").");
        }

        // 2) Hours worked.
        if (f.shiftMinutes() != null || f.workMinutes() != null) {
            sb.append(" Evidentirano");
            if (f.shiftMinutes() != null) sb.append(' ').append(minutesToHours(f.shiftMinutes())).append(" h smene");
            if (f.shiftMinutes() != null && f.workMinutes() != null) sb.append(", od čega");
            if (f.workMinutes() != null) sb.append(' ').append(minutesToHours(f.workMinutes())).append(" h rada");
            sb.append('.');
        }

        // 3) Absences / sick leave — only the non-zero parts.
        StringBuilder away = new StringBuilder();
        appendNonZeroHours(away, "plaćeno odsustvo", f.absencePaidMinutes());
        appendNonZeroHours(away, "neplaćeno odsustvo", f.absenceUnpaidMinutes());
        appendNonZeroHours(away, "bolovanje", f.sickLeaveMinutes());
        if (away.length() > 0) {
            sb.append(" Odsustva: ").append(away).append('.');
        }

        // 4) Output and scrap.
        if (f.quantity() != null && f.quantity() > 0) {
            sb.append(" Proizvedeno ").append(f.quantity()).append(" kom");
            if (f.scrap() != null && f.scrap() > 0) {
                sb.append(", škart ").append(f.scrap());
            }
            sb.append('.');
        }

        return sb.toString().strip();
    }

    private void appendNonZeroHours(StringBuilder sb, String label, Integer minutes) {
        if (minutes != null && minutes > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(minutesToHours(minutes)).append(" h ").append(label);
        }
    }

    /** Labelled "ključ: vrednost" lines; a null figure is simply omitted. */
    @SuppressWarnings("unused")
    private String buildUserMessage(Facts f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Radnik: ").append(f.name());
        if (f.employeeNo() != null) {
            sb.append(" (br. ").append(f.employeeNo()).append(')');
        }
        sb.append('\n');
        if (f.departmentName() != null) {
            sb.append("Sektor: ").append(f.departmentName()).append('\n');
        }
        sb.append("Mesec: ").append(f.month()).append('/').append(f.year()).append('\n');
        if (f.performanceRate() != null) {
            sb.append("Učinak: ").append(oneDecimal(f.performanceRate())).append("%\n");
        }
        appendHours(sb, "Sati smene", f.shiftMinutes());
        appendHours(sb, "Radni sati", f.workMinutes());
        appendHours(sb, "Plaćeno odsustvo", f.absencePaidMinutes());
        appendHours(sb, "Neplaćeno odsustvo", f.absenceUnpaidMinutes());
        appendHours(sb, "Bolovanje", f.sickLeaveMinutes());
        appendInt(sb, "Količina", f.quantity());
        appendInt(sb, "Škart", f.scrap());
        return sb.toString().strip();
    }

    private void appendHours(StringBuilder sb, String label, Integer minutes) {
        if (minutes != null) {
            sb.append(label).append(": ").append(minutesToHours(minutes)).append(" h\n");
        }
    }

    private void appendInt(StringBuilder sb, String label, Integer value) {
        if (value != null) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private String minutesToHours(int minutes) {
        return oneDecimal(BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
    }

    private String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Minutes → hours as a 1-decimal BigDecimal (HALF_UP); null stays null. */
    private BigDecimal minutesToHoursBd(Integer minutes) {
        if (minutes == null) return null;
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }

    private BigDecimal oneDecimalBd(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    /** Fully materialised numbers for one employee-month — no lazy references. */
    private record Facts(
            String name,
            String employeeNo,
            String departmentName,
            int month,
            int year,
            BigDecimal performanceRate,
            Integer shiftMinutes,
            Integer workMinutes,
            Integer absencePaidMinutes,
            Integer absenceUnpaidMinutes,
            Integer sickLeaveMinutes,
            Integer quantity,
            Integer scrap
    ) {
    }
}
