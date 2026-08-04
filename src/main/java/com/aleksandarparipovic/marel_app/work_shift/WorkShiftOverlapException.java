package com.aleksandarparipovic.marel_app.work_shift;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The shift being created runs into one the employee already has.
 *
 * <p>Carries the collision and the ways out, so the client can ASK rather than
 * report a failure. {@code ex_work_shifts_no_overlap} remains the guarantee —
 * this is raised before the insert so the constraint is never what the user
 * meets.
 *
 * <p>Two resolutions, and only when exactly one shift is in the way:
 *
 * <ul>
 *   <li>{@code TRIM} — the new shift stops where the existing one starts. A third
 *       shift 22:00–06:00 against a first shift that began at 05:00 becomes
 *       22:00–05:00. Nothing existing is touched.</li>
 *   <li>{@code MERGE} — the EXISTING shift is stretched to cover both and no new
 *       row is created. Deliberately that direction: the existing shift is the
 *       one that already carries work logs, and replacing it with a new row
 *       would take them with it.</li>
 * </ul>
 *
 * <p>When the new shift runs into one on EACH side there are three, and which one
 * is meant is a real question rather than a guess: absorb it into the earlier
 * shift, into the later one, or let it fill exactly the gap between them. Every
 * result is collision-free by construction — a merge stops where the shift on the
 * other side begins.
 *
 * <p>Any other arrangement of several conflicts is left alone. Two shifts both
 * starting inside the new one, or three of them, have no "previous and next" to
 * speak of, and picking for the user there would be guessing with somebody's pay.
 */
@Getter
public class WorkShiftOverlapException extends RuntimeException {

    public enum Resolution {
        /** Cut the collision off the new shift, from whichever side it is on. */
        TRIM,
        /** Stretch the single shift in the way over both. */
        MERGE,
        /** Two in the way: the earlier one absorbs the new shift, up to the later one. */
        MERGE_PREVIOUS,
        /** Two in the way: the later one absorbs it, back to the earlier one. */
        MERGE_NEXT,
        /** Two in the way: the new shift fills exactly the gap between them. */
        FIT_BETWEEN
    }

    /** One shift already in the way. */
    public record Conflict(Long id, String shiftName, OffsetDateTime startAt, OffsetDateTime endAt) {}

    /** A way out, with the interval it would actually produce. */
    public record Option(Resolution resolution, OffsetDateTime startAt, OffsetDateTime endAt,
                         String label) {}

    private final transient List<Conflict> conflicts;
    private final transient List<Option> options;

    public WorkShiftOverlapException(String message, List<Conflict> conflicts, List<Option> options) {
        super(message);
        this.conflicts = conflicts;
        this.options = options;
    }
}
