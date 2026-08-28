package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.customer.CustomerService;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.OrderCopySourceLineItemRow;
import com.aleksandarparipovic.marel_app.production_order.dto.OrderCopySourceRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
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
 * Finding the order you already made, so its lines can be copied.
 *
 * <p>Somebody writing an order remembers having made nearly this one before.
 * They remember ONE thing about it — the customer, the product, the quantity,
 * a phrase from the description — and never reliably which field that thing
 * lives in. So the box searches all of them, and this suite is the proof that
 * it does, field by field: each test hides the searched word in exactly one
 * place and nowhere else.
 *
 * <p>The two that would fail quietly and unrecognisably are worth naming. The
 * QUANTITY is an integer column read as text, which is a cast the database has
 * to agree to. The CUSTOMER is reached by a correlated EXISTS on a nullable
 * foreign key, which must not drop the internal orders that have no customer at
 * all.
 *
 * <p>Also asserted: every LIVE line of a matching order comes back, not only the
 * lines that matched — an order found by its customer's name has ten lines and
 * the reader may want any of them — and the quantity rows travel with them,
 * because the copy is made from exactly what is returned here.
 */
@Transactional
class OrderCopySourceSearchIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private CustomerService customerService;
    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private ProductRepository productRepository;

    /** A token only this test's orders carry, so a search can be scoped to them. */
    private final String tag = "kopija" + COUNTER.incrementAndGet();

    // ── Fixtures ────────────────────────────────────────────────────────────

    private CustomerDto aCustomer(String name, String code, String taxId) {
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setName(name);
        request.setCode(code);
        request.setTaxId(taxId);
        return customerService.create(request);
    }

    private Product aProduct(String name, String code) {
        return productRepository.save(Product.builder()
                .productName(name)
                .productCode(code)
                .active(true)
                .build());
    }

    private ProductionOrderCreateRequest.LineItemRequest aLine(
            Product product, String description, String note, int quantity
    ) {
        return new ProductionOrderCreateRequest.LineItemRequest(
                product.getId(), description, note, 1,
                List.of(new ProductionOrderCreateRequest.QuantityRequest(
                        quantity, LocalDate.of(2026, 9, 1))));
    }

    private ProductionOrderDetailDto anOrder(
            String name, String note, Long customerId, LocalDate createdOn,
            List<ProductionOrderCreateRequest.LineItemRequest> lines
    ) {
        int n = COUNTER.incrementAndGet();
        return productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-CP-" + n, name, customerId,
                note, false, createdOn, createdOn, null,
                false, false, false,
                List.of(), lines,
                null));
    }

    private Page<OrderCopySourceRow> find(String query) {
        return productionOrderService.searchCopySources(query, null, null, null, null, 0, 25);
    }

    private List<String> codesOf(Page<OrderCopySourceRow> page) {
        return page.getContent().stream().map(OrderCopySourceRow::code).toList();
    }

    // ── One box, every field ────────────────────────────────────────────────

    @Test
    @DisplayName("finds an order by its customer's name, code or tax id")
    void findsByCustomer() {
        CustomerDto customer = aCustomer("Livnica " + tag, "LIV" + tag, "PIB" + tag);
        ProductionOrderDetailDto wanted =
                anOrder("Nalog A", null, customer.getId(), LocalDate.of(2026, 3, 1), List.of());
        ProductionOrderDetailDto internal =
                anOrder("Nalog B", null, null, LocalDate.of(2026, 3, 1), List.of());

        assertThat(codesOf(find("livnica " + tag))).containsExactly(wanted.code());
        assertThat(codesOf(find("LIV" + tag))).containsExactly(wanted.code());
        assertThat(codesOf(find("pib" + tag))).containsExactly(wanted.code());

        // The internal order has no customer at all. The correlated EXISTS must
        // simply not match it — never drop it from every other search.
        assertThat(codesOf(find(internal.name()))).contains(internal.code());
    }

    @Test
    @DisplayName("finds an order by the product's name or code on one of its lines")
    void findsByProduct() {
        Product wanted = aProduct("Nosač " + tag, "ACME" + tag);
        ProductionOrderDetailDto order = anOrder(
                "Nalog sa nosačem", null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(wanted, "opis", "napomena", 10)));

        assertThat(codesOf(find("nosač " + tag))).containsExactly(order.code());
        assertThat(codesOf(find("acme" + tag))).containsExactly(order.code());
    }

    @Test
    @DisplayName("finds an order by the description the shop floor works from")
    void findsByWorkerDescription() {
        Product product = aProduct("Proizvod " + tag, null);
        ProductionOrderDetailDto order = anOrder(
                "Nalog sa opisom", null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(product, "brusiti pre " + tag, null, 5)));

        assertThat(codesOf(find("brusiti pre " + tag))).containsExactly(order.code());
    }

    @Test
    @DisplayName("finds an order by a line's note and by the order's own note")
    void findsByNotes() {
        Product product = aProduct("Proizvod " + tag, null);
        ProductionOrderDetailDto onLine = anOrder(
                "Nalog jedan", null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(product, null, "lakirati " + tag, 5)));
        ProductionOrderDetailDto onOrder = anOrder(
                "Nalog dva", "hitno " + tag, null, LocalDate.of(2026, 3, 1), List.of());

        assertThat(codesOf(find("lakirati " + tag))).containsExactly(onLine.code());
        assertThat(codesOf(find("hitno " + tag))).containsExactly(onOrder.code());
    }

    @Test
    @DisplayName("finds an order by the quantity on a line, read as text")
    void findsByQuantity() {
        Product product = aProduct("Proizvod " + tag, null);
        ProductionOrderDetailDto wanted = anOrder(
                "Nalog sto dvadeset " + tag, null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(product, null, null, 120)));
        ProductionOrderDetailDto other = anOrder(
                "Nalog sedam " + tag, null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(product, null, null, 7)));

        // An integer column compared as text — the cast the database has to agree
        // to, and the whole reason this test exists.
        List<String> found = codesOf(productionOrderService.searchCopySources(
                "120", null, null, null, null, 0, 25));

        assertThat(found).contains(wanted.code()).doesNotContain(other.code());
    }

    @Test
    @DisplayName("finds an order by its own code and name")
    void findsByCodeAndName() {
        ProductionOrderDetailDto order =
                anOrder("Rukohvati " + tag, null, null, LocalDate.of(2026, 3, 1), List.of());

        assertThat(codesOf(find(order.code().toLowerCase()))).containsExactly(order.code());
        assertThat(codesOf(find("rukohvati " + tag))).containsExactly(order.code());
    }

    // ── What comes back with a match ────────────────────────────────────────

    @Test
    @DisplayName("every live line comes back, with only the matching one marked")
    void returnsEveryLineAndMarksTheMatch() {
        Product plain = aProduct("Obican " + tag, null);
        Product wanted = aProduct("Nosač ventila " + tag, null);

        ProductionOrderDetailDto order = anOrder(
                "Nalog sa dve stavke", null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(plain, null, null, 3), aLine(wanted, null, null, 4)));

        OrderCopySourceRow row = find("nosač ventila " + tag).getContent().getFirst();

        assertThat(row.code()).isEqualTo(order.code());
        // BOTH lines — the reader may want the one that did not match.
        assertThat(row.lineItems()).hasSize(2);
        assertThat(row.lineItems().stream().filter(OrderCopySourceLineItemRow::matched))
                .singleElement()
                .satisfies(line -> assertThat(line.productName()).isEqualTo(wanted.getProductName()));
    }

    @Test
    @DisplayName("a line carries everything the copy is made from")
    void lineCarriesWhatTheCopyNeeds() {
        Product product = aProduct("Proizvod " + tag, "SIF" + tag);
        ProductionOrderDetailDto order = anOrder(
                "Nalog za kopiranje " + tag, null, null, LocalDate.of(2026, 3, 1),
                List.of(aLine(product, "brusiti i lakirati", "paziti na ivice", 42)));

        OrderCopySourceLineItemRow line =
                find("nalog za kopiranje " + tag).getContent().getFirst().lineItems().getFirst();

        assertThat(line.productId()).isEqualTo(product.getId());
        assertThat(line.productName()).isEqualTo(product.getProductName());
        assertThat(line.productCode()).isEqualTo("SIF" + tag);
        assertThat(line.productDescription()).isEqualTo("brusiti i lakirati");
        assertThat(line.note()).isEqualTo("paziti na ivice");
        assertThat(line.quantity()).isEqualTo(42);
        assertThat(line.quantities()).singleElement().satisfies(quantity -> {
            assertThat(quantity.quantity()).isEqualTo(42);
            assertThat(quantity.deliveryDeadline()).isEqualTo(LocalDate.of(2026, 9, 1));
        });

        assertThat(order.code()).isNotBlank();
    }

    // ── The three narrowings ────────────────────────────────────────────────

    @Test
    @DisplayName("narrows to one customer")
    void filtersByCustomer() {
        CustomerDto mine = aCustomer("Kupac A " + tag, null, null);
        CustomerDto theirs = aCustomer("Kupac B " + tag, null, null);
        ProductionOrderDetailDto ours = anOrder("Nalog " + tag, null, mine.getId(), LocalDate.of(2026, 3, 1), List.of());
        ProductionOrderDetailDto other = anOrder("Nalog " + tag, null, theirs.getId(), LocalDate.of(2026, 3, 1), List.of());

        List<String> found = codesOf(productionOrderService.searchCopySources(
                tag, mine.getId(), null, null, null, 0, 25));

        assertThat(found).contains(ours.code()).doesNotContain(other.code());
    }

    @Test
    @DisplayName("narrows to a span of creation dates, both ends included")
    void filtersByCreationDateSpan() {
        ProductionOrderDetailDto before = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 2, 28), List.of());
        ProductionOrderDetailDto onFrom = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 1), List.of());
        ProductionOrderDetailDto inside = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 3), List.of());
        ProductionOrderDetailDto onTo = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 5), List.of());
        ProductionOrderDetailDto after = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 6), List.of());

        List<String> span = codesOf(productionOrderService.searchCopySources(
                tag, null, null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5), 0, 25));

        // Both ends INCLUSIVE — the span is spoken as "from the 1st to the 5th".
        assertThat(span)
                .contains(onFrom.code(), inside.code(), onTo.code())
                .doesNotContain(before.code(), after.code());
    }

    @Test
    @DisplayName("one day given twice means that day, not nothing")
    void filtersBySingleDay() {
        ProductionOrderDetailDto onTheDay = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 3), List.of());
        ProductionOrderDetailDto theDayAfter = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 4), List.of());

        List<String> oneDay = codesOf(productionOrderService.searchCopySources(
                tag, null, null, LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 3), 0, 25));

        assertThat(oneDay).containsExactly(onTheDay.code());
        assertThat(oneDay).doesNotContain(theDayAfter.code());
    }

    @Test
    @DisplayName("a span given backwards is read the way it was meant")
    void spanGivenBackwards() {
        ProductionOrderDetailDto inside = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 3, 3), List.of());
        anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 4, 20), List.of());

        List<String> swapped = codesOf(productionOrderService.searchCopySources(
                tag, null, null, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 1), 0, 25));

        assertThat(swapped).containsExactly(inside.code());
    }

    @Test
    @DisplayName("with no query and no filters, the newest orders come first")
    void newestFirstByDefault() {
        ProductionOrderDetailDto older = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 1, 2), List.of());
        ProductionOrderDetailDto newer = anOrder("Nalog " + tag, null, null, LocalDate.of(2026, 8, 20), List.of());

        List<String> codes = codesOf(find(tag));

        assertThat(codes.indexOf(newer.code())).isLessThan(codes.indexOf(older.code()));
    }
}
