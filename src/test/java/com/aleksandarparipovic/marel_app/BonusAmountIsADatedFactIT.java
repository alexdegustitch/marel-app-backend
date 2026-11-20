package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryAmountPeriod;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryAmountPeriodRepository;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A bonus amount is a dated fact (V69).
 *
 * <p>Editing a category's amount used to overwrite the one column every later
 * recalculation reads, so raising a bonus silently repriced any older, unlocked
 * month. The amount now lives in {@code bonus_category_amount_periods}: an edit
 * CLOSES the open spell and opens a new one, the month being recalculated reads
 * the spell that covered it, and {@code bonus_categories.bonus_amount} is only
 * the mirror of what is in force today.
 */
@Transactional
class BonusAmountIsADatedFactIT extends AbstractIntegrationTest {

    @Autowired private BonusCategoryService service;
    @Autowired private BonusCategoryAmountPeriodRepository periods;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Belgrade"));

    private BonusCategory freshCategory(String no, String amount) {
        return service.create(BonusCategory.builder()
                .categoryNo(no)
                .categoryName("Test bonus " + no)
                .bonusAmount(new BigDecimal(amount))
                // The schema's legacy NOT NULL; the form no longer offers it.
                .minHours(BigDecimal.ZERO)
                .validFrom(LocalDate.of(2024, 1, 1))
                .active(true)
                .build());
    }

    private BonusCategory edited(BonusCategory base, String amount) {
        return BonusCategory.builder()
                .categoryNo(base.getCategoryNo())
                .categoryName(base.getCategoryName())
                .bonusAmount(new BigDecimal(amount))
                .validFrom(base.getValidFrom())
                .validUntil(base.getValidUntil())
                .description(base.getDescription())
                .build();
    }

    @Test
    @DisplayName("creating a category opens its first amount spell")
    void createOpensFirstSpell() {
        BonusCategory cat = freshCategory("T1", "6000");

        List<BonusCategoryAmountPeriod> history = periods.findHistoryFor(cat.getId());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getAmount()).isEqualByComparingTo("6000");
        assertThat(history.getFirst().getValidFrom()).isEqualTo(cat.getValidFrom());
        assertThat(history.getFirst().getValidTo()).isNull();
    }

    @Test
    @DisplayName("a dated amount change closes the old spell — an old month keeps its price")
    void datedChangeVersions() {
        BonusCategory cat = freshCategory("T2", "6000");
        LocalDate firstOfThisMonth = TODAY.withDayOfMonth(1);

        BonusCategory updated = service.update(cat.getId(), edited(cat, "7500"), firstOfThisMonth);

        // The old spell is CLOSED the day before, never rewritten.
        List<BonusCategoryAmountPeriod> history = periods.findHistoryFor(cat.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getAmount()).isEqualByComparingTo("7500");
        assertThat(history.get(0).getValidFrom()).isEqualTo(firstOfThisMonth);
        assertThat(history.get(0).getValidTo()).isNull();
        assertThat(history.get(1).getAmount()).isEqualByComparingTo("6000");
        assertThat(history.get(1).getValidTo()).isEqualTo(firstOfThisMonth.minusDays(1));

        // The seam the calculator reads: an old month stays 6000, this month is 7500.
        LocalDate olderMonth = firstOfThisMonth.minusMonths(2);
        assertThat(periods.findInForce(cat.getId(), olderMonth).orElseThrow().getAmount())
                .isEqualByComparingTo("6000");
        assertThat(periods.findInForce(cat.getId(), firstOfThisMonth).orElseThrow().getAmount())
                .isEqualByComparingTo("7500");

        // The mirror column follows what is in force TODAY.
        assertThat(updated.getBonusAmount()).isEqualByComparingTo("7500");
    }

    @Test
    @DisplayName("the same start date is a correction in place, not a second spell")
    void sameDateCorrectsInPlace() {
        BonusCategory cat = freshCategory("T3", "6000");
        LocalDate firstOfThisMonth = TODAY.withDayOfMonth(1);

        service.update(cat.getId(), edited(cat, "7500"), firstOfThisMonth);
        service.update(cat.getId(), edited(cat, "8000"), firstOfThisMonth);

        List<BonusCategoryAmountPeriod> history = periods.findHistoryFor(cat.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getAmount()).isEqualByComparingTo("8000");
        assertThat(history.get(0).getValidFrom()).isEqualTo(firstOfThisMonth);
    }

    @Test
    @DisplayName("an unchanged amount opens nothing")
    void unchangedAmountIsNoop() {
        BonusCategory cat = freshCategory("T4", "6000");

        service.update(cat.getId(), edited(cat, "6000"), TODAY);

        assertThat(periods.findHistoryFor(cat.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a change dated before the open spell is refused")
    void backdatedChangeRefused() {
        BonusCategory cat = freshCategory("T5", "6000");
        LocalDate firstOfThisMonth = TODAY.withDayOfMonth(1);
        service.update(cat.getId(), edited(cat, "7500"), firstOfThisMonth);

        assertThatThrownBy(() ->
                service.update(cat.getId(), edited(cat, "9000"), firstOfThisMonth.minusMonths(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posle početka");
    }
}
