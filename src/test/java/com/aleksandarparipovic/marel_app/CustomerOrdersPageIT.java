package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.customer.CustomerService;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOrderLineItemRow;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOrderRow;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.repository.ProductionOrderLineItemNoteRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer's own page: every order made for them, searched and sorted BY
 * THE SERVER.
 *
 * <p>The search is the part worth guarding. It is ONE box over six places —
 * the order's code, name and note, and on its line items the note, the note
 * list, and the product's name and code — because the person looking does not
 * know which of them somebody typed the words into. Every order that matches
 * anywhere is returned.
 *
 * <p>That creates the problem this suite exists for: an order can be in the
 * results because of words on a line item the order row never shows, and would
 * read as a result nobody asked for. The rows therefore come back with the
 * matching line items MARKED, and the marks must agree with the filter — the
 * filter is SQL {@code LIKE}, the marks are Java {@code contains}, and two
 * rules that disagree would return an order and then point at nothing on it.
 *
 * <p>What must stay true, and is asserted below:
 * <ul>
 *   <li>only this customer's orders come back, and they are sorted by the date
 *       asked for, in the direction asked for;
 *   <li>an order matches on its OWN code, name or note, on a line item's note,
 *       on a note in a line item's note list, and on the product's name or
 *       code;
 *   <li>whichever of those matched, the line item carrying it is marked — and
 *       the ones that did not are not;
 *   <li>a {@code %} somebody types is text, not a wildcard: it must not return
 *       every order the customer ever had;
 *   <li>with no search, nothing is marked.
 * </ul>
 */
@Transactional
class CustomerOrdersPageIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private CustomerService customerService;
    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductionOrderLineItemRepository lineItemRepository;
    @Autowired private ProductionOrderLineItemNoteRepository lineItemNoteRepository;

    // ── Fixtures ────────────────────────────────────────────────────────────

    private CustomerDto aCustomer() {
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setName("Kupac " + COUNTER.incrementAndGet());
        return customerService.create(request);
    }

    private Product aProduct() {
        return aProduct("Proizvod " + COUNTER.incrementAndGet(), null);
    }

    private Product aProduct(String name, String code) {
        return productRepository.save(Product.builder()
                .productName(name)
                .productCode(code)
                .active(true)
                .build());
    }

    /** An order for the customer whose single line item is for the given product. */
    private ProductionOrderDetailDto anOrderFor(Long customerId, Product product) {
        int n = COUNTER.incrementAndGet();
        return productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-CUST-" + n, "Nalog " + n, customerId,
                null, false, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1), null,
                false, false, false,
                List.of(),
                List.of(new ProductionOrderCreateRequest.LineItemRequest(
                        product.getId(), null, "bez posebne napomene", 1,
                        List.of(new ProductionOrderCreateRequest.QuantityRequest(1, null)))),
                null));
    }

    /** An order for the customer, with one line item carrying the given note. */
    private ProductionOrderDetailDto anOrder(
            Long customerId, LocalDate orderDate, String orderNote, String lineNote
    ) {
        int n = COUNTER.incrementAndGet();
        List<ProductionOrderCreateRequest.LineItemRequest> lines = lineNote == null
                ? List.of()
                : List.of(new ProductionOrderCreateRequest.LineItemRequest(
                        aProduct().getId(), null, lineNote, 1,
                        List.of(new ProductionOrderCreateRequest.QuantityRequest(5, null))));

        return productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-CUST-" + n, "Nalog " + n, customerId,
                orderNote, false, orderDate, orderDate, null,
                false, false, false,
                List.of(), lines,
                null));             // mailingListIds — this suite is about the listing
    }

    private Page<CustomerOrderRow> ordersOf(Long customerId, String search) {
        return productionOrderService.getCustomerOrders(
                customerId, search, Sort.Direction.DESC, "orderDate", 0, 25);
    }

    private List<String> codesOf(Page<CustomerOrderRow> page) {
        return page.getContent().stream().map(CustomerOrderRow::code).toList();
    }

    // ── Whose orders, in what order ─────────────────────────────────────────

    @Test
    @DisplayName("only this customer's orders come back")
    void listsOnlyTheirOwnOrders() {
        CustomerDto mine = aCustomer();
        CustomerDto theirs = aCustomer();

        ProductionOrderDetailDto ours = anOrder(mine.getId(), LocalDate.of(2026, 3, 1), null, null);
        ProductionOrderDetailDto other = anOrder(theirs.getId(), LocalDate.of(2026, 3, 1), null, null);

        assertThat(codesOf(ordersOf(mine.getId(), null)))
                .contains(ours.code())
                .doesNotContain(other.code());
    }

    @Test
    @DisplayName("sorted by the date asked for, in the direction asked for")
    void sortsByDate() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto older = anOrder(customer.getId(), LocalDate.of(2026, 1, 10), null, null);
        ProductionOrderDetailDto newer = anOrder(customer.getId(), LocalDate.of(2026, 6, 20), null, null);

        assertThat(codesOf(productionOrderService.getCustomerOrders(
                customer.getId(), null, Sort.Direction.DESC, "orderDate", 0, 25)))
                .containsExactly(newer.code(), older.code());

        assertThat(codesOf(productionOrderService.getCustomerOrders(
                customer.getId(), null, Sort.Direction.ASC, "orderDate", 0, 25)))
                .containsExactly(older.code(), newer.code());
    }

    @Test
    @DisplayName("an unknown sort field falls back to the order date rather than failing")
    void unknownSortFieldFallsBack() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto older = anOrder(customer.getId(), LocalDate.of(2026, 1, 10), null, null);
        ProductionOrderDetailDto newer = anOrder(customer.getId(), LocalDate.of(2026, 6, 20), null, null);

        assertThat(codesOf(productionOrderService.getCustomerOrders(
                customer.getId(), null, Sort.Direction.DESC, "napraviMiGresku", 0, 25)))
                .containsExactly(newer.code(), older.code());
    }

    // ── The search ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the order's own note is searched, case-insensitively and part-way")
    void matchesTheOrdersOwnNote() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto wanted =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 1), "Bez PAKOVANJA, hitno", null);
        ProductionOrderDetailDto unwanted =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 2), "obična isporuka", null);

        Page<CustomerOrderRow> found = ordersOf(customer.getId(), "pakovanja");

        assertThat(codesOf(found)).contains(wanted.code()).doesNotContain(unwanted.code());
        assertThat(found.getContent().getFirst().matched()).isTrue();
    }

    @Test
    @DisplayName("the order's code and name are searched by the same box")
    void matchesTheOrdersCodeAndName() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 1), null, null);

        assertThat(codesOf(ordersOf(customer.getId(), order.code().toLowerCase())))
                .containsExactly(order.code());
        assertThat(codesOf(ordersOf(customer.getId(), order.name())))
                .containsExactly(order.code());
        assertThat(ordersOf(customer.getId(), order.code()).getContent().getFirst().matched())
                .isTrue();
    }

    @Test
    @DisplayName("the product's name and code are searched, and mark their line item")
    void matchesTheProductNameAndCode() {
        CustomerDto customer = aCustomer();
        Product wanted = aProduct("Nosač ventila " + COUNTER.incrementAndGet(), "ACME-220");
        ProductionOrderDetailDto onWanted = anOrderFor(customer.getId(), wanted);
        ProductionOrderDetailDto onOther = anOrderFor(customer.getId(), aProduct());

        Page<CustomerOrderRow> byName = ordersOf(customer.getId(), "nosač ventila");
        assertThat(codesOf(byName)).contains(onWanted.code()).doesNotContain(onOther.code());

        CustomerOrderRow row = byName.getContent().getFirst();
        assertThat(row.matched()).isFalse();          // nothing on the order itself
        assertThat(row.matchedLineItemCount()).isEqualTo(1);
        assertThat(row.lineItems().getFirst().productCode()).isEqualTo("ACME-220");

        Page<CustomerOrderRow> byCode = ordersOf(customer.getId(), "acme-220");
        assertThat(codesOf(byCode)).contains(onWanted.code()).doesNotContain(onOther.code());
        assertThat(byCode.getContent().getFirst().lineItems().getFirst().matched()).isTrue();
    }

    @Test
    @DisplayName("a line item's note is searched too, and the line item is marked")
    void matchesALineItemNoteAndMarksIt() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 1), "ništa posebno", "lakirati u sivo");

        Page<CustomerOrderRow> found = ordersOf(customer.getId(), "LAKIRATI");

        assertThat(codesOf(found)).containsExactly(order.code());

        CustomerOrderRow row = found.getContent().getFirst();
        assertThat(row.matched()).isFalse();           // nothing on the order itself
        assertThat(row.matchedLineItemCount()).isEqualTo(1);
        assertThat(row.lineItems()).hasSize(1);
        assertThat(row.lineItems().getFirst().matched()).isTrue();
    }

    @Test
    @DisplayName("a note in a line item's note list is searched, and marks its line item")
    void matchesALineItemNoteRowAndMarksIt() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 1), null, "prva napomena");

        ProductionOrderLineItem lineItem = lineItemRepository
                .findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(order.id())
                .getFirst();
        ProductionOrderLineItemNote extra = new ProductionOrderLineItemNote();
        extra.setProductionOrderLineItem(lineItem);
        extra.setOrderNote(2);
        extra.setNote("pakovati u drvene sanduke");
        extra.setIsActive(true);
        lineItemNoteRepository.save(extra);

        Page<CustomerOrderRow> found = ordersOf(customer.getId(), "drvene sanduke");

        assertThat(codesOf(found)).containsExactly(order.code());

        CustomerOrderLineItemRow line = found.getContent().getFirst().lineItems().getFirst();
        assertThat(line.matched()).isTrue();
        assertThat(line.notes()).anyMatch(note -> note.matched() && note.note().contains("sanduke"));
    }

    @Test
    @DisplayName("only the line item that carries the words is marked")
    void marksOnlyTheMatchingLineItem() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order = productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-CUST-" + COUNTER.incrementAndGet(), "Nalog sa dve stavke", customer.getId(),
                null, false, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1), null,
                false, false, false,
                List.of(),
                List.of(
                        new ProductionOrderCreateRequest.LineItemRequest(
                                aProduct().getId(), null, "obična obrada", 1,
                                List.of(new ProductionOrderCreateRequest.QuantityRequest(1, null))),
                        new ProductionOrderCreateRequest.LineItemRequest(
                                aProduct().getId(), null, "lakirati u sivo", 2,
                                List.of(new ProductionOrderCreateRequest.QuantityRequest(1, null)))),
                null));

        CustomerOrderRow row = ordersOf(customer.getId(), "lakirati").getContent().getFirst();

        assertThat(row.code()).isEqualTo(order.code());
        assertThat(row.lineItems()).hasSize(2);
        assertThat(row.matchedLineItemCount()).isEqualTo(1);
        assertThat(row.lineItems().stream().filter(CustomerOrderLineItemRow::matched))
                .singleElement()
                .satisfies(line -> assertThat(line.lineOrder()).isEqualTo(2));
    }

    @Test
    @DisplayName("a percent sign somebody types is text, not a wildcard")
    void wildcardsTypedByAPersonAreLiteral() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto plain =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 1), "obična isporuka", null);
        ProductionOrderDetailDto literal =
                anOrder(customer.getId(), LocalDate.of(2026, 2, 2), "škart do 5% dozvoljen", null);

        Page<CustomerOrderRow> found = ordersOf(customer.getId(), "5%");

        assertThat(codesOf(found)).contains(literal.code()).doesNotContain(plain.code());
    }

    @Test
    @DisplayName("with no search, every order comes back and nothing is marked")
    void withoutASearchNothingIsMarked() {
        CustomerDto customer = aCustomer();
        anOrder(customer.getId(), LocalDate.of(2026, 2, 1), "napomena naloga", "napomena stavke");
        anOrder(customer.getId(), LocalDate.of(2026, 2, 2), null, null);

        Page<CustomerOrderRow> found = ordersOf(customer.getId(), "   ");

        assertThat(found.getTotalElements()).isEqualTo(2);
        assertThat(found.getContent()).allSatisfy(row -> {
            assertThat(row.matched()).isFalse();
            assertThat(row.matchedLineItemCount()).isZero();
        });
    }
}
