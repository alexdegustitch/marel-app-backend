package com.aleksandarparipovic.marel_app.work_calendar_day;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes official Serbian non-working public holidays for a given year, per
 * "Zakon o državnim i verskim praznicima u Republici Srbiji".
 *
 * Best-effort implementation, not a legal source of truth — Serbian holiday law can
 * be amended, and every day this produces remains manually editable in the work
 * calendar UI after auto-fill. Verify against the current law before relying on this
 * for payroll-sensitive years.
 */
@Component
public class SerbianHolidayCalculator {

    /**
     * Returns holiday date -> label for the given year, including the Sunday-shift
     * substitute non-working day for fixed-date holidays (Article 2 of the law: if a
     * holiday falls on a Sunday, the first following working day is also non-working).
     * The Orthodox Easter block is excluded from the Sunday-shift rule since it
     * already spans Friday-Monday.
     */
    public Map<LocalDate, String> getHolidays(int year) {
        Map<LocalDate, String> holidays = new LinkedHashMap<>();

        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.JANUARY, 1), "Nova godina");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.JANUARY, 2), "Nova godina");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.JANUARY, 7), "Božić");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.FEBRUARY, 15), "Dan državnosti Srbije");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.FEBRUARY, 16), "Dan državnosti Srbije");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.MAY, 1), "Praznik rada");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.MAY, 2), "Praznik rada");
        addFixedHolidayWithShift(holidays, LocalDate.of(year, Month.NOVEMBER, 11), "Dan primirja u Prvom svetskom ratu");

        LocalDate orthodoxEaster = orthodoxEasterSunday(year);
        holidays.putIfAbsent(orthodoxEaster.minusDays(2), "Veliki petak");
        holidays.putIfAbsent(orthodoxEaster.minusDays(1), "Velika subota");
        holidays.putIfAbsent(orthodoxEaster, "Vaskrs");
        holidays.putIfAbsent(orthodoxEaster.plusDays(1), "Uskršnji ponedeljak");

        return holidays;
    }

    private void addFixedHolidayWithShift(Map<LocalDate, String> holidays, LocalDate date, String label) {
        holidays.putIfAbsent(date, label);

        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            LocalDate substitute = date.plusDays(1);
            while (substitute.getDayOfWeek() == DayOfWeek.SATURDAY
                    || substitute.getDayOfWeek() == DayOfWeek.SUNDAY
                    || holidays.containsKey(substitute)) {
                substitute = substitute.plusDays(1);
            }
            holidays.putIfAbsent(substitute, "Zamena za praznik (" + label + ")");
        }
    }

    /**
     * Orthodox Easter Sunday (Gregorian calendar date) via the Meeus Julian algorithm,
     * converted from the Julian to the Gregorian calendar.
     */
    private LocalDate orthodoxEasterSunday(int year) {
        int a = year % 4;
        int b = year % 7;
        int c = year % 19;
        int d = (19 * c + 15) % 30;
        int e = (2 * a + 4 * b - d + 34) % 7;
        int month = (d + e + 114) / 31;
        int day = ((d + e + 114) % 31) + 1;

        LocalDate julianEaster = LocalDate.of(year, month, day);
        return julianEaster.plusDays(julianToGregorianOffsetDays(year));
    }

    // Standard Julian-Gregorian day offset: 13 for 1900-2099, 14 for 2100-2199, etc.
    private int julianToGregorianOffsetDays(int year) {
        return (year / 100) - (year / 400) - 2;
    }
}
