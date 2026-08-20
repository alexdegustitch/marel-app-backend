package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BonusMinHoursRuleHistoryRepository extends JpaRepository<BonusMinHoursRuleHistory, Long> {

    /** The row in force for a month, if the history has one open. */
    Optional<BonusMinHoursRuleHistory> findByPeriodAndValidUntilIsNull(LocalDate period);

    /** A month's whole history, newest first. */
    List<BonusMinHoursRuleHistory> findByPeriodOrderByValidFromDesc(LocalDate period);
}
