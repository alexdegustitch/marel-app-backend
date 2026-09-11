package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeavePeriod;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeavePeriodRepository;
import com.aleksandarparipovic.marel_app.user.dto.UserWorkContextDto;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayType;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The work-status slice of a colleague's profile — is this person at work today,
 * and is a holiday of theirs coming up.
 *
 * <p>Kept OUT of {@link UserService} on purpose: it reaches into leave periods and
 * the work calendar, dependencies that the account service has no other reason to
 * carry. It is also the one part of the profile that is not simply a field on the
 * user row — it is computed from two other records against today's date.
 *
 * <p>Every classification rule it applies is stated on {@link UserWorkContextDto}.
 * The one number it owns is how far ahead "bliži se godišnji" looks.
 */
@Service
@RequiredArgsConstructor
public class UserWorkContextService {

    /**
     * How far ahead an annual leave counts as "coming up" — about two months.
     * A GO further out than this is real but not yet news a colleague needs on
     * the profile; nearer than this, the badge says it is approaching.
     */
    private static final int UPCOMING_VACATION_DAYS = 60;

    /** The category marker for annual leave (godišnji odmor). */
    private static final String VACATION_CATEGORY_NO = "GO";
    /** The category TYPE marker for sick leave (bolovanje), across its kinds. */
    private static final String SICK_LEAVE_TYPE = "SICK_LEAVE";

    private final UserRepository userRepository;
    private final EmployeeLeavePeriodRepository leaveRepository;
    private final WorkCalendarDayRepository calendarRepository;

    /**
     * The profile's work context for one account, or null when the account is not
     * a worker's — there is no work life to state for an office account.
     */
    @Transactional(readOnly = true)
    public UserWorkContextDto forUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));

        Employee employee = user.getEmployee();
        if (employee == null) {
            return null;
        }

        LocalDate today = LocalDate.now();

        UserWorkContextDto.TodayKind todayKind;
        LocalDate todayUntil = null;
        String todayNote = null;

        /*
         * Today's leave first — it is about THIS person and outranks the calendar.
         * The list is ordered by start date; the first period of each kind wins,
         * and the kinds are checked sick → vacation → other, the order the badge
         * shows them in.
         */
        List<EmployeeLeavePeriod> todaysLeave = leaveRepository.findIntersecting(employee.getId(), today, today);
        EmployeeLeavePeriod sick = firstOfType(todaysLeave, SICK_LEAVE_TYPE);
        EmployeeLeavePeriod vacation = firstWithCategoryNo(todaysLeave, VACATION_CATEGORY_NO);
        EmployeeLeavePeriod otherAbsence = firstOtherLeave(todaysLeave);

        if (sick != null) {
            todayKind = UserWorkContextDto.TodayKind.SICK_LEAVE;
            todayUntil = sick.getDateTo();
            todayNote = sick.getWorkCodeCategory().getCategoryName();
        } else if (vacation != null) {
            todayKind = UserWorkContextDto.TodayKind.VACATION;
            todayUntil = vacation.getDateTo();
            todayNote = vacation.getWorkCodeCategory().getCategoryName();
        } else if (otherAbsence != null) {
            todayKind = UserWorkContextDto.TodayKind.OTHER_ABSENCE;
            todayUntil = otherAbsence.getDateTo();
            todayNote = otherAbsence.getWorkCodeCategory().getCategoryName();
        } else if (isNonWorkingHoliday(today)) {
            todayKind = UserWorkContextDto.TodayKind.NON_WORKING_DAY;
            todayNote = calendarRepository.findByCalendarDate(today)
                    .map(WorkCalendarDay::getLabel)
                    .orElse(null);
        } else {
            todayKind = UserWorkContextDto.TodayKind.WORKING;
        }

        EmployeeLeavePeriod upcomingVacation = nearestUpcomingVacation(employee.getId(), today);

        return UserWorkContextDto.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getFullName())
                .departmentName(employee.getDepartment() != null ? employee.getDepartment().getName() : null)
                .employmentStartDate(employee.getEmploymentStartDate())
                .todayKind(todayKind)
                .todayUntil(todayUntil)
                .todayNote(todayNote)
                .upcomingVacationFrom(upcomingVacation != null ? upcomingVacation.getDateFrom() : null)
                .upcomingVacationTo(upcomingVacation != null ? upcomingVacation.getDateTo() : null)
                .build();
    }

    private EmployeeLeavePeriod firstOfType(List<EmployeeLeavePeriod> periods, String type) {
        return periods.stream()
                .filter(p -> type.equals(p.getWorkCodeCategory().getType()))
                .findFirst()
                .orElse(null);
    }

    private EmployeeLeavePeriod firstWithCategoryNo(List<EmployeeLeavePeriod> periods, String categoryNo) {
        return periods.stream()
                .filter(p -> categoryNo.equals(p.getWorkCodeCategory().getCategoryNo()))
                .findFirst()
                .orElse(null);
    }

    /** A leave today that is neither sick leave nor annual leave — unpaid, a trip. */
    private EmployeeLeavePeriod firstOtherLeave(List<EmployeeLeavePeriod> periods) {
        return periods.stream()
                .filter(p -> !SICK_LEAVE_TYPE.equals(p.getWorkCodeCategory().getType()))
                .filter(p -> !VACATION_CATEGORY_NO.equals(p.getWorkCodeCategory().getCategoryNo()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether today is a non-worked day of the shared calendar — a public holiday
     * or a collective shutdown, honouring an explicit working override. Ordinary
     * weekends (NON_WORKING) are deliberately excluded: they are not news.
     */
    private boolean isNonWorkingHoliday(LocalDate today) {
        Optional<WorkCalendarDay> day = calendarRepository.findByCalendarDate(today);
        if (day.isEmpty()) {
            return false;
        }
        WorkCalendarDay calendarDay = day.get();
        if (Boolean.TRUE.equals(calendarDay.getWorkingOverride())) {
            return false;
        }
        return calendarDay.getDayType() == WorkCalendarDayType.HOLIDAY
                || calendarDay.getDayType() == WorkCalendarDayType.COLLECTIVE_LEAVE;
    }

    /**
     * The nearest annual leave that STARTS after today and within the look-ahead
     * window. A GO already covering today is excluded — that is stated as the
     * today status, not as something approaching.
     */
    private EmployeeLeavePeriod nearestUpcomingVacation(Long employeeId, LocalDate today) {
        LocalDate from = today.plusDays(1);
        LocalDate to = today.plusDays(UPCOMING_VACATION_DAYS);

        return leaveRepository.findIntersecting(employeeId, from, to).stream()
                .filter(p -> VACATION_CATEGORY_NO.equals(p.getWorkCodeCategory().getCategoryNo()))
                .filter(p -> p.getDateFrom().isAfter(today))
                .min(Comparator.comparing(EmployeeLeavePeriod::getDateFrom))
                .orElse(null);
    }
}
