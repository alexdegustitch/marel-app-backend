package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production order list opens on the newest order, and stays paged.
 *
 * <p>The list now defaults to creation date, newest first. Two things about
 * that sort are the server's to get right, and neither is something the request
 * can say for itself:
 *
 * <ul>
 *   <li><b>Nulls last.</b> PostgreSQL puts nulls FIRST on a DESC sort. An order
 *       whose creation date was never filled in — an import, an order written
 *       before the field existed — would therefore open the list under "newest
 *       first", which is the opposite of what the words say.
 *   <li><b>A tie-break.</b> Orders written on the same day are the ordinary
 *       case, and rows tied on the whole sort key have no order the database is
 *       obliged to keep between one query and the next. Without the id last,
 *       page 2 can repeat an order from page 1 and drop another entirely.
 * </ul>
 *
 * <p>Both are asserted here rather than trusted, because both fail SILENTLY:
 * the list still renders, still counts right, and is simply wrong about which
 * orders it is showing.
 */
@Transactional
class ProductionOrderCreationDateSortIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private ProductionOrderService productionOrderService;

    /** A marker only this test's orders carry, so the page is its own. */
    private final String marker = "SORT-" + COUNTER.incrementAndGet() + "-";

    private ProductionOrderDetailDto anOrder(String suffix, LocalDate creationDate) {
        return productionOrderService.create(new ProductionOrderCreateRequest(
                marker + suffix, marker + suffix, null,
                null, false, creationDate, creationDate, null,
                false, false, false,
                List.of(), List.of(),
                null));          // mailingListIds — this suite is about the sort
    }

    private SearchRequest sortedByCreationDate(SearchRequest.Direction direction, int page, int size) {
        SearchRequest request = new SearchRequest();

        SearchRequest.Pagination pagination = new SearchRequest.Pagination();
        pagination.setPage(page);
        pagination.setSize(size);
        request.setPagination(pagination);

        SearchRequest.SortField sort = new SearchRequest.SortField();
        sort.setField("creationDate");
        sort.setDirection(direction);
        request.setSort(List.of(sort));

        // The marker keeps the page to this test's own orders — the global search
        // runs over code and name, and both carry it.
        request.setGlobalSearch(marker);

        return request;
    }

    private List<String> codesOf(Page<ProductionOrderCardRow> page) {
        return page.getContent().stream().map(ProductionOrderCardRow::code).toList();
    }

    @Test
    @DisplayName("newest created first, and an order with no creation date does not jump the queue")
    void newestCreatedFirstWithNullsLast() {
        ProductionOrderDetailDto oldest = anOrder("A", LocalDate.of(2026, 1, 5));
        ProductionOrderDetailDto newest = anOrder("B", LocalDate.of(2026, 7, 30));
        ProductionOrderDetailDto middle = anOrder("C", LocalDate.of(2026, 4, 12));
        ProductionOrderDetailDto undated = anOrder("D", null);

        List<String> codes = codesOf(productionOrderService.searchAll(
                sortedByCreationDate(SearchRequest.Direction.DESC, 0, 25)));

        assertThat(codes).containsExactly(
                newest.code(), middle.code(), oldest.code(), undated.code());
    }

    @Test
    @DisplayName("ascending reverses the dates, and the undated order still sorts last")
    void oldestFirstKeepsNullsLast() {
        ProductionOrderDetailDto oldest = anOrder("A", LocalDate.of(2026, 1, 5));
        ProductionOrderDetailDto newest = anOrder("B", LocalDate.of(2026, 7, 30));
        ProductionOrderDetailDto undated = anOrder("C", null);

        List<String> codes = codesOf(productionOrderService.searchAll(
                sortedByCreationDate(SearchRequest.Direction.ASC, 0, 25)));

        assertThat(codes).containsExactly(oldest.code(), newest.code(), undated.code());
    }

    @Test
    @DisplayName("orders created on the same day keep a fixed order across pages")
    void tiedDatesPageWithoutRepeatingOrLosing() {
        LocalDate sameDay = LocalDate.of(2026, 5, 5);
        for (int i = 0; i < 6; i++) {
            anOrder("T" + i, sameDay);
        }

        List<String> firstPage = codesOf(productionOrderService.searchAll(
                sortedByCreationDate(SearchRequest.Direction.DESC, 0, 3)));
        List<String> secondPage = codesOf(productionOrderService.searchAll(
                sortedByCreationDate(SearchRequest.Direction.DESC, 1, 3)));

        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(3);

        // Six orders across two pages of three: six DIFFERENT orders. Without the
        // id tie-break this is exactly where one turns up twice.
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);

        // And the same page asked for twice is the same page.
        assertThat(codesOf(productionOrderService.searchAll(
                sortedByCreationDate(SearchRequest.Direction.DESC, 0, 3))))
                .containsExactlyElementsOf(firstPage);
    }
}
