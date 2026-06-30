package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BonusEligibilityRuleRepository extends JpaRepository<BonusEligibilityRule, Long> {

    List<BonusEligibilityRule> findByArchivedAtIsNullOrderByPeriodDesc();

    List<BonusEligibilityRule> findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(LocalDate period);

    Optional<BonusEligibilityRule> findByIdAndArchivedAtIsNull(Long id);

    boolean existsByPeriodAndArchivedAtIsNull(LocalDate period);

    boolean existsByPeriodAndArchivedAtIsNullAndIdNot(LocalDate period, Long id);
}

