package com.aleksandarparipovic.marel_app.absence_record;

/**
 * What became of an unpaid absence once the overtime bank had its say.
 *
 * <p>Only absences of the NO category ever carry one of these. A paid absence —
 * godišnji odmor, plaćeno odsustvo, službeno odsutan — has {@code outcome NULL},
 * because there is nothing for the bank to buy back and nothing about the
 * weekend bonus for it to change.
 */
public enum AbsenceOutcome {

    /**
     * Unpaid absence. The bank did not cover the whole shift — it may have
     * covered part of it, which {@code compensatedMinutes} records — and the day
     * still spoils that week's weekend bonus.
     */
    NO,

    /**
     * Neradni dan. The bank covered the WHOLE shift, so the day is treated as one
     * the employee was never expected to work: it does not spoil the weekend
     * bonus. A single ND work log is written across the shift to say so.
     */
    ND
}
