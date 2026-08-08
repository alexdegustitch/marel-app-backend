package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.ProbationPolicy;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriod;
import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriodRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingTypeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An employee on probation is paid no weekend bonus.
 *
 * <p>Two halves, and both are needed: {@link ProbationPolicy} decides WHO is on
 * probation on a given date, and the mapping-type registry decides WHICH remaps
 * that withholds. {@code DailyRecalcService.resolveApplicableMappingTypes}
 * removes the second from the types it would otherwise apply.
 *
 * <p><b>Why this is not a compensation scheme.</b> A scheme is an assigned dated
 * period and exactly one must cover every work date; probation is derived from
 * the employment dates, so as a scheme somebody would have to open and close a
 * period by hand for every employee, and forgetting the second half would break
 * work ENTRY rather than just the bonus. Schemes are also mutually exclusive
 * while probation crosses them — a foreign worker can be on probation — so it
 * would need a scheme per combination. A scheme answers what work is WORTH; this
 * answers what work BECOMES in context, which is the mapping's question.
 */
@Transactional
class ProbationWeekendBonusIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ProbationPolicy probationPolicy;
    @Autowired private WorkCodeCategoryMappingTypeRepository mappingTypeRepository;
    @Autowired private EmployeeEmploymentPeriodRepository periodRepository;
    @Autowired private EntityManager entityManager;

    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 4);

    // ─── which remaps probation withholds ───────────────────────────────────

    @Test
    @DisplayName("only the weekend bonus is withheld during probation")
    void registrySaysWhichRemapsAreWithheld() {
        assertThat(mappingTypeRepository.findCodesWithheldDuringProbation())
                .containsExactly("WEEKEND_BONUS");

        // The other two apply from the first day. Asserted rather than assumed,
        // because one careless UPDATE on the registry is all it takes to stop
        // paying a night shift.
        assertThat(mappingTypeRepository.findByCode("NIGHT_SHIFT_BONUS").orElseThrow()
                .getAppliesDuringProbation()).isTrue();
        assertThat(mappingTypeRepository.findByCode("MULTIPLE_MACHINES_BONUS").orElseThrow()
                .getAppliesDuringProbation()).isTrue();
    }

    @Test
    @DisplayName("mapping_type is a foreign key now, so a typo cannot masquerade as configuration")
    void everyMappingTypeIsRegistered() {
        // Before the registry, DailyRecalcService's switch ended in
        // `default -> ignore`, so a misspelt type produced a row that looked
        // configured and silently did nothing.
        @SuppressWarnings("unchecked")
        List<String> unregistered = entityManager.createNativeQuery("""
                SELECT DISTINCT m.mapping_type
                FROM work_code_category_mappings m
                WHERE NOT EXISTS (SELECT 1 FROM work_code_category_mapping_types t
                                  WHERE t.code = m.mapping_type)
                """).getResultList();

        assertThat(unregistered).isEmpty();
    }

    @Test
    @DisplayName("an unregistered mapping type cannot be inserted at all")
    void unregisteredMappingTypeIsRejected() {
        assertThat(entityManager.createNativeQuery("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'fk_wccm_mapping_type'
                """).getSingleResult())
                .describedAs("the foreign key is what closes the silent-typo hole")
                .extracting(Object::toString).isEqualTo("1");
    }

    // ─── who is on probation, and when ──────────────────────────────────────

    @Test
    @DisplayName("a date inside the period is probation")
    void insideThePeriod() {
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY.minusDays(5), 30), SATURDAY))
                .isTrue();
    }

    @Test
    @DisplayName("the first day is probation")
    void firstDayIsInclusive() {
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY, 30), SATURDAY)).isTrue();
    }

    @Test
    @DisplayName("the last day is probation")
    void lastDayIsInclusive() {
        // Started 30 days before, 30 days of grace, so probation_end_date IS the
        // Saturday. Inclusive — an off-by-one here pays a bonus that should not be.
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY.minusDays(30), 30), SATURDAY))
                .isTrue();
    }

    @Test
    @DisplayName("the day after the period is not probation")
    void dayAfterIsNot() {
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY.minusDays(31), 30), SATURDAY))
                .isFalse();
    }

    @Test
    @DisplayName("a date before employment started is not probation")
    void beforeEmploymentIsNot() {
        // Not a real scenario, but the answer must be "no" rather than "yes by
        // arithmetic": this decides whether to WITHHOLD money.
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY.plusDays(1), 30), SATURDAY))
                .isFalse();
    }

    @Test
    @DisplayName("zero grace days is NO probation, including on the very first day")
    void zeroGraceIsNoProbation() {
        // The rehire case. probation_end_date equals the start date, so the
        // arithmetic alone would call the first day probation — and a returning
        // employee is given zero precisely to say the opposite.
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY, 0), SATURDAY)).isFalse();
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY.minusDays(5), 0), SATURDAY))
                .isFalse();
    }

    @Test
    @DisplayName("an unknown or absent employee is not on probation")
    void missingDataDoesNotWithhold() {
        assertThat(probationPolicy.isOnProbation(null, SATURDAY)).isFalse();
        assertThat(probationPolicy.isOnProbation(-1L, SATURDAY)).isFalse();
        assertThat(probationPolicy.isOnProbation(employee(SATURDAY, 30), null)).isFalse();
    }

    // ─── a returning employee ───────────────────────────────────────────────

    @Test
    @DisplayName("a rehired employee serves no new probation")
    void rehireServesNoNewProbation() {
        // The reason employment became a table. As two columns on the employee
        // row, a rehire moved employment_start_date forward and the GENERATED
        // probation_end_date followed it — handing a returning employee a fresh
        // 30 days without anybody deciding that.
        Long employeeId = employee(LocalDate.of(2024, 1, 1), 30);
        closeAndRehire(employeeId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1));

        assertThat(probationPolicy.isOnProbation(employeeId, SATURDAY))
                .as("the new spell defaults to 0 grace days, so no probation")
                .isFalse();
    }

    @Test
    @DisplayName("an administrator can give a returning employee a new probation")
    void rehireProbationCanBeSetExplicitly() {
        Long employeeId = employee(LocalDate.of(2024, 1, 1), 30);
        EmployeeEmploymentPeriod second =
                closeAndRehire(employeeId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1));
        second.setNormGraceDays(30);
        periodRepository.saveAndFlush(second);
        entityManager.flush();
        entityManager.clear();

        assertThat(probationPolicy.isOnProbation(employeeId, SATURDAY)).isTrue();
    }

    @Test
    @DisplayName("probation is read from the spell the work date falls in, not the latest")
    void anEarlierSpellKeepsItsOwnProbation() {
        // A day inside the FIRST spell's probation is still probation, even though
        // the employee has since left and come back. Reading the employee row —
        // which mirrors only the latest spell — would answer about the wrong one.
        Long employeeId = employee(LocalDate.of(2026, 1, 5), 30);
        closeAndRehire(employeeId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1));

        assertThat(probationPolicy.isOnProbation(employeeId, LocalDate.of(2026, 1, 10)))
                .as("inside the first spell's probation")
                .isTrue();
        assertThat(probationPolicy.isOnProbation(employeeId, LocalDate.of(2026, 3, 1)))
                .as("inside the first spell, after its probation")
                .isFalse();
    }

    @Test
    @DisplayName("a date in the gap between spells is not probation")
    void theGapIsNotProbation() {
        Long employeeId = employee(LocalDate.of(2024, 1, 1), 30);
        closeAndRehire(employeeId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 10));

        assertThat(probationPolicy.isOnProbation(employeeId, LocalDate.of(2026, 7, 5)))
                .as("not employed at all on that date")
                .isFalse();
    }

    @Test
    @DisplayName("the employee row mirrors the LATEST spell")
    void mirrorFollowsTheLatestSpell() {
        Long employeeId = employee(LocalDate.of(2024, 1, 1), 30);
        closeAndRehire(employeeId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1));

        Employee reloaded = employeeRepository.findById(employeeId).orElseThrow();
        entityManager.refresh(reloaded);
        assertThat(reloaded.getEmploymentStartDate())
                .as("date of employment is the start of the current spell")
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(reloaded.getEmploymentEndDate()).isNull();
        assertThat(reloaded.getProbationEndDate())
                .as("0 grace days, so the mirrored probation end is the start itself")
                .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("two overlapping spells are refused by the database")
    void spellsCannotOverlap() {
        Long employeeId = employee(LocalDate.of(2024, 1, 1), 30);
        Employee employee = employeeRepository.findById(employeeId).orElseThrow();

        assertThatThrownBy(() -> {
            periodRepository.saveAndFlush(EmployeeEmploymentPeriod.builder()
                    .employee(employee)
                    .startedOn(LocalDate.of(2025, 1, 1))
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Closes the current spell and opens a new one, as a rehire does. */
    private EmployeeEmploymentPeriod closeAndRehire(Long employeeId, LocalDate endFirst, LocalDate startSecond) {
        EmployeeEmploymentPeriod first = periodRepository.findLatestOne(employeeId).orElseThrow();
        first.setEndedOn(endFirst);
        periodRepository.saveAndFlush(first);

        EmployeeEmploymentPeriod second = periodRepository.saveAndFlush(
                EmployeeEmploymentPeriod.builder()
                        .employee(first.getEmployee())
                        .startedOn(startSecond)
                        .build());
        entityManager.flush();
        entityManager.clear();
        return periodRepository.findById(second.getId()).orElseThrow();
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    /**
     * An employee with ONE spell of employment.
     *
     * <p>The period is what probation is read from now — the employee columns are
     * only a trigger-maintained mirror of the latest one.
     */
    private Long employee(LocalDate start, int graceDays) {
        Employee employee = fixture.scenario().build().employee();
        periodRepository.findLatestOne(employee.getId()).ifPresent(p -> {
            p.setStartedOn(start);
            p.setNormGraceDays(graceDays);
            periodRepository.saveAndFlush(p);
        });
        entityManager.flush();
        entityManager.clear();
        return employee.getId();
    }
}
