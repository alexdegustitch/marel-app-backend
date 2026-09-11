package com.aleksandarparipovic.marel_app.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * The directory-safe slice of a colleague's WORK life, shown on their profile.
 *
 * <p>The profile page is open to everyone signed in, but the full employee record
 * — pay, scheme, history — is not. So this is a deliberately narrow view: which
 * department, since when, whether the person is at work today, and whether a
 * holiday of theirs is coming up. Nothing here is anything the reader may change,
 * and nothing here is pay.
 *
 * <p>Null throughout when the account is not linked to a worker (administration
 * and office accounts): there is no work life to state, and the caller shows the
 * account-level facts alone.
 */
@Getter
@Builder
public class UserWorkContextDto {

    /**
     * What is true about this person's work TODAY, in priority order.
     *
     * <ul>
     *   <li>{@code SICK_LEAVE} — on sick leave (a leave period whose category is
     *       {@code type = 'SICK_LEAVE'}).</li>
     *   <li>{@code VACATION} — on annual leave (category {@code categoryNo = 'GO'}).</li>
     *   <li>{@code OTHER_ABSENCE} — any other recorded leave today (unpaid, a work
     *       trip): the person is away, but it is neither sick leave nor a holiday.</li>
     *   <li>{@code NON_WORKING_DAY} — no personal leave, but the day itself is not
     *       worked: a public holiday or a collective shutdown. Deliberately NOT an
     *       ordinary weekend, which would light every profile every Saturday.</li>
     *   <li>{@code WORKING} — none of the above; a normal day at work.</li>
     * </ul>
     */
    public enum TodayKind {
        WORKING,
        SICK_LEAVE,
        VACATION,
        OTHER_ABSENCE,
        NON_WORKING_DAY
    }

    /** The worker this account is, or null when it is none. */
    private final Long employeeId;
    private final String employeeName;

    /** Which department, and since when employed — the profile's "Organizacija". */
    private final String departmentName;
    private final LocalDate employmentStartDate;

    /** What is true today (never null when there is a linked employee). */
    private final TodayKind todayKind;

    /**
     * The last day of the absence covering today — the "do DD.MM." on the badge,
     * so a colleague knows when the person is back. Null for WORKING and for a
     * NON_WORKING_DAY (which is a single day, not a span).
     */
    private final LocalDate todayUntil;

    /**
     * A word for WHY, when today is an absence or a non-working day: the leave
     * category name, or the holiday's label. Null when working.
     */
    private final String todayNote;

    /**
     * The nearest annual leave (GO) that STARTS within the look-ahead window,
     * for the "bliži se godišnji" hint. Null when none is scheduled soon or the
     * person is already on it (that is stated by {@link #todayKind} instead).
     */
    private final LocalDate upcomingVacationFrom;
    private final LocalDate upcomingVacationTo;
}
