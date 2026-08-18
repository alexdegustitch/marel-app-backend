package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.search.PageableBuilder;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operation that is MISSING the sorted value sorts last — either direction.
 *
 * <p>WHY THIS EXISTS. The operations screen sorts by norm, norm date and
 * quantity, and plenty of operations have none of them. A missing norm is not a
 * low norm: the question the sort answers is "which operations have the
 * highest/lowest X", never "where do the blanks fall". Left to the database the
 * answer changes with the direction — PostgreSQL puts NULLs last ascending and
 * first descending — so descending by norm would have opened with a screenful
 * of blanks.
 *
 * <p>The ordering is built by hand in {@code OperationRepositoryImpl}, which no
 * test executed; this runs it against the real database in both directions.
 */
@Transactional
class OperationSortNullsLastIT extends AbstractIntegrationTest {

    @Autowired private OperationRepository operationRepository;
    @Autowired private ProductRepository productRepository;

    @Test
    @DisplayName("operations without a norm sort last, ascending and descending")
    void missingNormsSortLast() {
        Product product = productRepository.save(Product.builder()
                .productName("Sort fixture " + System.nanoTime())
                .active(true)
                .build());

        operationRepository.save(operation(product, "sa normom 10", 10));
        operationRepository.save(operation(product, "bez norme A", null));
        operationRepository.save(operation(product, "sa normom 90", 90));
        operationRepository.save(operation(product, "bez norme B", null));

        List<OperationWithProductInfoRow> ascending = rowsForProduct(product, "minNorm", SearchRequest.Direction.ASC);
        List<OperationWithProductInfoRow> descending = rowsForProduct(product, "minNorm", SearchRequest.Direction.DESC);

        assertThat(ascending).extracting(OperationWithProductInfoRow::getMinNorm)
                .containsExactly(10, 90, null, null);
        assertThat(descending).extracting(OperationWithProductInfoRow::getMinNorm)
                .containsExactly(90, 10, null, null);
    }

    @Test
    @DisplayName("the same rule holds for norm date and quantity in the assembly")
    void missingDatesAndQuantitiesSortLast() {
        Product product = productRepository.save(Product.builder()
                .productName("Sort fixture " + System.nanoTime())
                .active(true)
                .build());

        Operation dated = operation(product, "sa datumom", 5);
        dated.setNormDate(LocalDate.of(2026, 3, 14));
        dated.setUnitsPerProduct(4);
        operationRepository.save(dated);

        // Norm date and quantity both absent.
        operationRepository.save(operation(product, "bez ičega", 5));

        assertThat(rowsForProduct(product, "normDate", SearchRequest.Direction.DESC))
                .extracting(OperationWithProductInfoRow::getNormDate)
                .containsExactly(LocalDate.of(2026, 3, 14), null);

        assertThat(rowsForProduct(product, "unitsPerProduct", SearchRequest.Direction.DESC))
                .extracting(OperationWithProductInfoRow::getUnitsPerProduct)
                .containsExactly(4, null);
    }

    /**
     * A norm is all-or-nothing in the database: `chk_operations_norm_required_valid`
     * requires min AND max together whenever the operation is normed, so an
     * operation "without a norm" is one with `normRequired = false` and both
     * columns empty — which is exactly the row this test is about.
     */
    private Operation operation(Product product, String name, Integer minNorm) {
        Operation operation = new Operation();
        operation.setProduct(product);
        operation.setOpName(name);
        operation.setMinNorm(minNorm);
        operation.setMaxNorm(minNorm == null ? null : minNorm + 10);
        operation.setNormRequired(minNorm != null);
        return operation;
    }

    /** The screen's own query shape: search all, sorted by one field. */
    private List<OperationWithProductInfoRow> rowsForProduct(
            Product product,
            String field,
            SearchRequest.Direction direction
    ) {
        SearchRequest request = new SearchRequest();
        request.setSort(List.of(sortField(field, direction)));

        SearchRequest.FilterField productFilter = new SearchRequest.FilterField();
        productFilter.setField("productId");
        productFilter.setOperator(SearchRequest.Operator.EQ);
        productFilter.setValue(product.getId());
        request.setFilters(List.of(productFilter));

        Page<OperationWithProductInfoRow> page =
                operationRepository.searchNative(request, PageableBuilder.from(request));
        return page.getContent();
    }

    private SearchRequest.SortField sortField(String field, SearchRequest.Direction direction) {
        SearchRequest.SortField sort = new SearchRequest.SortField();
        sort.setField(field);
        sort.setDirection(direction);
        return sort;
    }
}
