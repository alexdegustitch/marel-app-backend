package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_log.interval.ShiftIntervalResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-backed checks for the coefficient source behind verified time.
 *
 * <p>The interval arithmetic itself is covered by {@code WorkIntervalCalculatorTest}
 * without a database. What can only be proven against a real schema is that the
 * coefficient is genuinely loaded from configuration, that it is resolved as of the
 * work date rather than today, and that the columns the recalc engine writes exist
 * with the types the entity claims.
 */
@Transactional
class ShiftVerifiedTimeIT extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private ShiftIntervalResolver intervalResolver;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private WorkCodeCategoryMappingRepository mappingRepository;

    private static final LocalDate VALID_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate VALID_UNTIL = LocalDate.of(2026, 9, 30);

    private WorkCodeCategory category(String no, double multiplier, boolean allowsParallelWork) {
        WorkCodeCategory category = WorkCodeCategory.builder()
                .categoryNo(no)
                .categoryName("Category " + no)
                .type("WORK")
                .isPaid(true)
                .normMultiplier(multiplier)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(true)
                .allowsParallelWork(allowsParallelWork)
                .displayOrder(0)
                // NOT NULL in the schema even though the entity leaves it nullable.
                .baseCategory(false)
                .build();
        return categoryRepository.saveAndFlush(category);
    }

    private void mapping(WorkCodeCategory source, WorkCodeCategory target, LocalDate from, LocalDate until) {
        mappingRepository.saveAndFlush(WorkCodeCategoryMapping.builder()
                .sourceCategory(source)
                .targetCategory(target)
                .mappingType(ShiftIntervalResolver.MULTIPLE_MACHINES_BONUS)
                .isActive(true)
                .validFrom(from)
                .validUntil(until)
                .build());
    }

    @Test
    @DisplayName("the PLB coefficient is read from the mapped target category, not hardcoded")
    void plbCoefficientComesFromConfiguration() {
        WorkCodeCategory pl = category("IT-PL-1", 1.0d, true);
        WorkCodeCategory plb = category("IT-PLB-1", 1.75d, true);
        mapping(pl, plb, VALID_FROM, VALID_UNTIL);

        BigDecimal resolved = intervalResolver.resolvePlbCoefficient(LocalDate.of(2026, 5, 20));

        assertThat(resolved).isEqualByComparingTo("1.75");
    }

    @Test
    @DisplayName("the coefficient valid on the WORK date is used, not the one valid today")
    void coefficientIsResolvedAsOfTheWorkDate() {
        WorkCodeCategory pl = category("IT-PL-2", 1.0d, true);
        WorkCodeCategory plb = category("IT-PLB-2", 1.60d, true);
        mapping(pl, plb, VALID_FROM, VALID_UNTIL);

        // Inside the validity window.
        assertThat(intervalResolver.resolvePlbCoefficient(VALID_FROM)).isEqualByComparingTo("1.60");
        assertThat(intervalResolver.resolvePlbCoefficient(VALID_UNTIL)).isEqualByComparingTo("1.60");

        // Outside it there is no configured PLB coefficient for that day.
        assertThat(intervalResolver.resolvePlbCoefficient(VALID_FROM.minusDays(1))).isNull();
        assertThat(intervalResolver.resolvePlbCoefficient(VALID_UNTIL.plusDays(1))).isNull();
    }

    @Test
    @DisplayName("an archived or inactive mapping stops supplying a coefficient")
    void inactiveMappingIsIgnored() {
        WorkCodeCategory pl = category("IT-PL-3", 1.0d, true);
        WorkCodeCategory plb = category("IT-PLB-3", 2.0d, true);

        WorkCodeCategoryMapping saved = mappingRepository.saveAndFlush(WorkCodeCategoryMapping.builder()
                .sourceCategory(pl)
                .targetCategory(plb)
                .mappingType(ShiftIntervalResolver.MULTIPLE_MACHINES_BONUS)
                .isActive(false)
                .validFrom(VALID_FROM)
                .validUntil(VALID_UNTIL)
                .build());
        assertThat(saved.getId()).isNotNull();

        assertThat(intervalResolver.resolvePlbCoefficient(LocalDate.of(2026, 5, 20))).isNull();
    }

    @Test
    @DisplayName("parallel capability round-trips through the real column")
    void parallelCapabilityPersists() {
        WorkCodeCategory parallel = category("IT-PAR", 1.2d, true);
        WorkCodeCategory ordinary = category("IT-ORD", 1.0d, false);
        entityManager.clear();

        assertThat(categoryRepository.findById(parallel.getId()))
                .get()
                .extracting(WorkCodeCategory::getAllowsParallelWork)
                .isEqualTo(true);
        assertThat(categoryRepository.findById(ordinary.getId()))
                .get()
                .extracting(WorkCodeCategory::getAllowsParallelWork)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("the columns the recalc engine writes exist with the declared types")
    void verifiedMinutesColumnsExist() {
        @SuppressWarnings("unchecked")
        List<Object[]> columns = entityManager.createNativeQuery("""
                        select column_name, data_type, numeric_scale, is_nullable
                        from information_schema.columns
                        where table_name = 'daily_reports'
                          and column_name in ('total_verified_minutes', 'total_pl_minutes', 'total_plb_minutes')
                        order by column_name
                        """)
                .getResultList();

        assertThat(columns).hasSize(3);

        assertThat(columns).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo("total_verified_minutes");
            assertThat(row[1]).isEqualTo("numeric");
            assertThat(((Number) row[2]).intValue()).isEqualTo(4);
            // Nullable by design: historical reports are not backfilled.
            assertThat(row[3]).isEqualTo("YES");
        });

        assertThat(columns).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo("total_pl_minutes");
            assertThat(row[1]).isEqualTo("integer");
        });
        assertThat(columns).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo("total_plb_minutes");
            assertThat(row[1]).isEqualTo("integer");
        });
    }

    @Test
    @DisplayName("allows_parallel_work is part of the real schema, not dev auto-DDL")
    void parallelWorkColumnIsInTheSchema() {
        Object result = entityManager.createNativeQuery("""
                        select is_nullable
                        from information_schema.columns
                        where table_name = 'work_code_categories'
                          and column_name = 'allows_parallel_work'
                        """)
                .getSingleResult();

        assertThat(result).isEqualTo("NO");
    }
}
