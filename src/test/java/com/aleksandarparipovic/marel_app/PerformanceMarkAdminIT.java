package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMark;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMarkAdminService;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMarkRepository;
import com.aleksandarparipovic.marel_app.performance_mark.dto.PerformanceMarkAdminDto;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.performance_mark.dto.UpsertPerformanceMarkRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The performance-marks šifarnik against the real schema: V57's versioning
 * rule (same "važi od" corrects in place, a later one closes the version and
 * opens a new one), the overlap refusals that mirror ex_pm_no_overlap, and
 * the restore that must not land on a reoccupied period.
 */
@Transactional
class PerformanceMarkAdminIT extends AbstractIntegrationTest {

    @Autowired private PerformanceMarkAdminService service;
    @Autowired private PerformanceMarkRepository repository;

    /** Every test gets its own mark value, so the GiST guard never crosses tests. */
    private static final AtomicInteger MARK = new AtomicInteger(100);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);

    // ── fixtures ────────────────────────────────────────────────────────────

    private static BigDecimal freshMark() {
        return BigDecimal.valueOf(MARK.incrementAndGet());
    }

    private static UpsertPerformanceMarkRequest request(BigDecimal mark, String amount, String unit, LocalDate from) {
        UpsertPerformanceMarkRequest r = new UpsertPerformanceMarkRequest();
        r.setMark(mark);
        r.setAmount(new BigDecimal(amount));
        r.setAmountUnit(unit);
        r.setValidFrom(from);
        return r;
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a created mark is listed, current and editable")
    void createAndList() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "-40.00", "RSD_PER_HOUR", FROM));

        assertThat(created.amount()).isEqualByComparingTo("-40.00");
        assertThat(created.amountUnit()).isEqualTo("RSD_PER_HOUR");
        assertThat(created.current()).isTrue();
        assertThat(created.editable()).isTrue();

        List<PerformanceMarkAdminDto> all = service.listAll();
        assertThat(all).anySatisfy(d -> assertThat(d.id()).isEqualTo(created.id()));
    }

    @Test
    @DisplayName("a second period overlapping the open one is refused with a Serbian message")
    void overlapIsRefused() {
        BigDecimal mark = freshMark();
        service.create(request(mark, "5.00", "PERCENT", FROM));

        assertThatThrownBy(() -> service.create(request(mark, "10.00", "PERCENT", FROM.plusDays(10))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("već ima verziju");
    }

    @Test
    @DisplayName("the same start date corrects the open version in place")
    void sameDateCorrectsInPlace() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "-40.00", "RSD_PER_HOUR", FROM));

        PerformanceMarkAdminDto corrected =
                service.update(created.id(), request(mark, "-45.00", "RSD_PER_HOUR", FROM));

        assertThat(corrected.id()).isEqualTo(created.id());
        assertThat(corrected.amount()).isEqualByComparingTo("-45.00");
        assertThat(versionsOf(mark)).hasSize(1);
    }

    @Test
    @DisplayName("a later start date closes the open version and opens a new one")
    void laterDateOpensNewVersion() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "-40.00", "RSD_PER_HOUR", FROM));

        LocalDate move = FROM.plusMonths(1);
        PerformanceMarkAdminDto next =
                service.update(created.id(), request(mark, "-50.00", "RSD_PER_HOUR", move));

        assertThat(next.id()).isNotEqualTo(created.id());
        assertThat(next.validFrom()).isEqualTo(move);
        assertThat(next.validTo()).isNull();

        PerformanceMark closed = repository.findById(created.id()).orElseThrow();
        assertThat(closed.getValidTo()).isEqualTo(move.minusDays(1));
        assertThat(closed.getAmount()).isEqualByComparingTo("-40.00");
        assertThat(versionsOf(mark)).hasSize(2);
    }

    @Test
    @DisplayName("an earlier start date is refused")
    void earlierDateIsRefused() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "5.00", "PERCENT", FROM));

        assertThatThrownBy(() -> service.update(created.id(), request(mark, "7.00", "PERCENT", FROM.minusDays(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posle početka");
    }

    @Test
    @DisplayName("a closed version is history and refuses the edit")
    void closedVersionIsRefused() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "-40.00", "RSD_PER_HOUR", FROM));
        service.update(created.id(), request(mark, "-50.00", "RSD_PER_HOUR", FROM.plusMonths(1)));

        assertThatThrownBy(() -> service.update(created.id(), request(mark, "-60.00", "RSD_PER_HOUR", FROM)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("istorija");
    }

    @Test
    @DisplayName("an unchanged value with an end date ends the mark without a successor")
    void endDateClosesWithoutSuccessor() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto created = service.create(request(mark, "5.00", "PERCENT", FROM));

        UpsertPerformanceMarkRequest end = request(mark, "5.00", "PERCENT", FROM);
        end.setValidTo(FROM.plusMonths(2));
        PerformanceMarkAdminDto ended = service.update(created.id(), end);

        assertThat(ended.id()).isEqualTo(created.id());
        assertThat(ended.validTo()).isEqualTo(FROM.plusMonths(2));
        assertThat(versionsOf(mark)).hasSize(1);
    }

    @Test
    @DisplayName("an unknown unit is refused with the units spelled out")
    void badUnitIsRefused() {
        assertThatThrownBy(() -> service.create(request(freshMark(), "5.00", "EUR", FROM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENT")
                .hasMessageContaining("RSD_PER_HOUR");
    }

    @Test
    @DisplayName("restore refuses to land on a period reoccupied while the row was archived")
    void restoreIntoOccupiedPeriodIsRefused() {
        BigDecimal mark = freshMark();
        PerformanceMarkAdminDto first = service.create(request(mark, "-40.00", "RSD_PER_HOUR", FROM));

        // Archive directly — the service path is password-signed, and the
        // password check is not what this test is about.
        PerformanceMark archived = repository.findById(first.id()).orElseThrow();
        archived.setArchivedAt(OffsetDateTime.now());
        repository.saveAndFlush(archived);

        service.create(request(mark, "-50.00", "RSD_PER_HOUR", FROM));

        assertThatThrownBy(() -> service.restore(first.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("već ima verziju");
    }

    private List<PerformanceMark> versionsOf(BigDecimal mark) {
        return repository.findAll().stream()
                .filter(p -> p.getMark().compareTo(mark) == 0)
                .toList();
    }
}
