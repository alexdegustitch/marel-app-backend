package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BonusMinHoursRuleRepository extends JpaRepository<BonusMinHoursRule, Long> {

    List<BonusMinHoursRule> findByArchivedAtIsNullOrderByPeriodDesc();

    Optional<BonusMinHoursRule> findByIdAndArchivedAtIsNull(Long id);

    Optional<BonusMinHoursRule> findByPeriodAndArchivedAtIsNull(LocalDate period);

    boolean existsByPeriodAndArchivedAtIsNull(LocalDate period);

    boolean existsByPeriodAndArchivedAtIsNullAndIdNot(LocalDate period, Long id);
}

