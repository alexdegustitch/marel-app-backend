package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a month's minimum-hours history is written.
 *
 * <p>Both paths that can change the number go through here — the calendar sync and a person
 * setting or clearing an override — so the history cannot end up telling two different
 * stories depending on which door the change came in by.
 */
@Service
@RequiredArgsConstructor
public class BonusMinHoursHistoryRecorder {

    private final BonusMinHoursRuleHistoryRepository historyRepository;

    /**
     * Records where a month stands, if that is not already what the history says.
     *
     * <p>Unchanged values write nothing. The calendar recomputes every month on every edit to
     * any day in it, and almost always arrives at the number already there; recording those
     * would bury the handful of real changes under thousands of rows saying nothing happened.
     *
     * <p>A change closes the open interval at the same instant the new one opens, so the
     * timeline has no gap and no overlap — which is also what the database's partial unique
     * index insists on.
     */
    @Transactional
    public void record(LocalDate period,
                       Integer systemMinNumHours,
                       Integer manualMinNumHours,
                       BonusMinHoursRuleHistory.Source source,
                       Long changedBy,
                       String note) {

        Optional<BonusMinHoursRuleHistory> open = historyRepository.findByPeriodAndValidUntilIsNull(period);

        if (open.isPresent()
                && Objects.equals(open.get().getSystemMinNumHours(), systemMinNumHours)
                && Objects.equals(open.get().getManualMinNumHours(), manualMinNumHours)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        /*
         * Flushed, not just saved. Within one flush JPA orders statements by operation type —
         * every insert before every update — so a plain save() would send the NEW open row
         * before the UPDATE that closes the old one, and the partial unique index would refuse
         * it. The index is right to: for that instant there really would be two rows claiming
         * to be in force. Closing first makes the order match the meaning.
         */
        open.ifPresent(previous -> {
            previous.setValidUntil(now);
            historyRepository.saveAndFlush(previous);
        });

        historyRepository.save(BonusMinHoursRuleHistory.builder()
                .period(period)
                .systemMinNumHours(systemMinNumHours)
                .manualMinNumHours(manualMinNumHours)
                .source(source)
                .validFrom(now)
                .changedBy(changedBy)
                .note(note)
                .build());
    }
}
