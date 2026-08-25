package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.customer.CustomerRepository;
import com.aleksandarparipovic.marel_app.customer.CustomerService;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerUpdateRequest;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who the work is for.
 *
 * <p>The customer was previously spelled into the order's free-text name, so
 * "everything we made for this customer" was not a question the data could
 * answer. It is a real table now, and an order may point at one — or at nothing,
 * which is the ordinary case and has to stay a first-class answer rather than a
 * gap somebody is nagged to fill.
 *
 * <p>What must stay true, and is asserted below:
 * <ul>
 *   <li>an order without a customer saves, and reads back as having none;
 *   <li>an id that names nothing is REFUSED rather than quietly booked to
 *       nobody, which would leave somebody believing they had recorded it;
 *   <li>a customer who has since been deactivated does not make the orders
 *       already booked against them unsaveable;
 *   <li>"no code" is NULL and not the empty string, or the partial unique index
 *       would let the second customer without a code collide with the first;
 *   <li>deactivating sets archived_at and restoring clears it, by trigger;
 *   <li>set_updated_at() still only fires on a real change — this migration adds
 *       a trigger to that shared function and must never redefine it.
 * </ul>
 */
@Transactional
class CustomerOfAnOrderIT extends AbstractIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private EntityManager entityManager;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private CustomerDto aCustomer() {
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setName("Kupac " + COUNTER.incrementAndGet());
        return customerService.create(request);
    }

    private ProductionOrderCreateRequest anOrderFor(Long customerId) {
        int n = COUNTER.incrementAndGet();
        return new ProductionOrderCreateRequest(
                "NAL-" + n, "Nalog " + n, customerId,
                null, false, null, null, null, false, false, false,
                List.of(), List.of());
    }

    // ── The link ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an order can be for a customer, and reads back as being for them")
    void orderCarriesItsCustomer() {
        CustomerDto customer = aCustomer();

        ProductionOrderDetailDto created = productionOrderService.create(anOrderFor(customer.getId()));

        assertThat(created.customerId()).isEqualTo(customer.getId());
        assertThat(created.customerName()).isEqualTo(customer.getName());
    }

    /*
     * The ordinary case, and the one every existing order is in. If this ever
     * starts failing, the column has quietly become required.
     */
    @Test
    @DisplayName("an order for nobody outside is not a missing value")
    void orderWithoutCustomer() {
        ProductionOrderDetailDto created = productionOrderService.create(anOrderFor(null));

        assertThat(created.customerId()).isNull();
        assertThat(created.customerName()).isNull();
    }

    /*
     * Silently booking it to nobody is the failure that matters here: the order
     * saves, the screen says it saved, and the customer somebody selected is
     * simply not on it.
     */
    @Test
    @DisplayName("a customer id that names nothing is refused, not ignored")
    void unknownCustomerIsRefused() {
        assertThatThrownBy(() -> productionOrderService.create(anOrderFor(-1L)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /*
     * Pickers only offer active customers, but an order booked before the
     * customer was deactivated still has to be editable. Refusing here would
     * make that order unsaveable until somebody worked out why.
     */
    @Test
    @DisplayName("a deactivated customer does not strand the orders already booked to them")
    void deactivatedCustomerKeepsItsOrdersEditable() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order = productionOrderService.create(anOrderFor(customer.getId()));

        customerService.deactivate(customer.getId());
        assertThat(customerService.options())
                .noneMatch(option -> option.id().equals(customer.getId()));

        ProductionOrderDetailDto saved = productionOrderService.update(
                order.id(),
                new ProductionOrderUpdateRequest(
                        "Novo ime", customer.getId(),
                        null, false, null, null, null, false, false, false,
                        List.of(), List.of()));

        assertThat(saved.customerId()).isEqualTo(customer.getId());
    }

    /* The form sends everything back on every save, so null means "no customer". */
    @Test
    @DisplayName("saving without a customer clears the one that was there")
    void customerCanBeCleared() {
        CustomerDto customer = aCustomer();
        ProductionOrderDetailDto order = productionOrderService.create(anOrderFor(customer.getId()));

        ProductionOrderDetailDto saved = productionOrderService.update(
                order.id(),
                new ProductionOrderUpdateRequest(
                        order.name(), null,
                        null, false, null, null, null, false, false, false,
                        List.of(), List.of()));

        assertThat(saved.customerId()).isNull();
    }

    // ── The customer record itself ──────────────────────────────────────────

    /*
     * THE ONE WITH TEETH.
     *
     * uq_customers_code_ci and uq_customers_tax_id are PARTIAL — they skip NULL.
     * An empty box stored as "" would be a value, so the second customer without
     * a code would be refused for colliding with the first. Nothing on screen
     * would explain it.
     */
    @Test
    @DisplayName("an empty code is no code, not the empty string")
    void blankCodeIsNull() {
        CustomerCreateRequest first = new CustomerCreateRequest();
        first.setName("Prvi bez šifre");
        first.setCode("   ");
        first.setTaxId("");
        CustomerDto saved = customerService.create(first);

        assertThat(saved.getCode()).isNull();
        assertThat(saved.getTaxId()).isNull();

        CustomerCreateRequest second = new CustomerCreateRequest();
        second.setName("Drugi bez šifre");
        second.setCode("");
        // Would collide on '' if blanks were stored as written.
        assertThat(customerService.create(second).getCode()).isNull();
    }

    @Test
    @DisplayName("two customers cannot share a code, whatever the casing")
    void codeIsUniqueCaseInsensitively() {
        CustomerCreateRequest first = new CustomerCreateRequest();
        first.setName("Prvi");
        first.setCode("Acme");
        customerService.create(first);

        CustomerCreateRequest second = new CustomerCreateRequest();
        second.setName("Drugi");
        second.setCode("ACME");

        assertThatThrownBy(() -> customerService.create(second))
                .isInstanceOf(ConflictException.class);
    }

    /* Saving a customer without touching its code must not find itself. */
    @Test
    @DisplayName("keeping your own code is not a collision with yourself")
    void ownCodeIsNotACollision() {
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setName("Kupac sa šifrom");
        request.setCode("K-100");
        CustomerDto created = customerService.create(request);

        CustomerUpdateRequest update = new CustomerUpdateRequest();
        update.setName("Preimenovan");
        update.setCode("K-100");

        assertThat(customerService.update(created.getId(), update).getCode()).isEqualTo("K-100");
    }

    /*
     * archived_at answers WHEN, not merely whether. Both directions are the
     * database's own doing (trg_01/trg_02), so nothing in the service sets it
     * and nothing there can forget to.
     */
    @Test
    @DisplayName("deactivating stamps archived_at, and coming back clears it")
    void archivedAtFollowsActive() {
        CustomerDto customer = aCustomer();
        assertThat(customer.getArchivedAt()).isNull();

        customerService.deactivate(customer.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(customerRepository.findById(customer.getId()).orElseThrow().getArchivedAt())
                .as("deactivating should stamp the moment it happened")
                .isNotNull();

        customerService.restore(customer.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(customerRepository.findById(customer.getId()).orElseThrow().getArchivedAt())
                .as("a restored customer is not archived, so the stamp has to go")
                .isNull();
    }

    /*
     * A GUARD ON SOMETHING THIS FEATURE DOES NOT OWN.
     *
     * The DDL this table arrived with carried `CREATE OR REPLACE FUNCTION
     * set_updated_at()` with a body that bumps updated_at unconditionally. That
     * function is shared by about thirty tables and deliberately does NOT: it
     * fires only when a column other than updated_at actually changed. Replacing
     * it would make every no-op UPDATE in the database move a timestamp that
     * feeds the audit trail and the recalculation queues — with nothing on any
     * screen to show for it.
     *
     * Reading the source back is the only way to notice; the schema is otherwise
     * identical either way.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("set_updated_at still only fires on a real change")
    void sharedUpdatedAtFunctionIsIntact() {
        String source = (String) entityManager
                .createNativeQuery("SELECT prosrc FROM pg_proc WHERE proname = 'set_updated_at'")
                .getSingleResult();

        assertThat(source)
                .as("set_updated_at must keep its guard — about thirty tables run it")
                .containsIgnoringCase("IS DISTINCT FROM");
    }

    /*
     * A customer's name and tax id end up on documents, so who changed them and
     * when is not a detail. audit_trigger_fn resolves the table by NAME against
     * audit_tables — a trigger without the registration writes nothing and says
     * nothing about it, which is exactly the failure that goes unnoticed.
     */
    @Test
    @DisplayName("a change to a customer reaches the audit trail")
    void customerChangesAreAudited() {
        CustomerDto customer = aCustomer();

        CustomerUpdateRequest rename = new CustomerUpdateRequest();
        rename.setName("Preimenovani kupac");
        customerService.update(customer.getId(), rename);
        entityManager.flush();

        Number rows = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM audit_logs l
                JOIN audit_tables t ON t.id = l.table_id
                WHERE t.table_name = 'customers' AND l.record_id = :id
                """)
                .setParameter("id", customer.getId())
                .getSingleResult();

        // The insert and the rename both.
        assertThat(rows.intValue()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("only active customers are offered for new work")
    void optionsAreActiveOnly() {
        CustomerDto active = aCustomer();
        CustomerDto gone = aCustomer();
        customerService.deactivate(gone.getId());

        assertThat(customerService.options()).anyMatch(o -> o.id().equals(active.getId()));
        assertThat(customerService.options()).noneMatch(o -> o.id().equals(gone.getId()));
    }
}
