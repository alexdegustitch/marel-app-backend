package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.customer.CustomerService;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerCreateRequest;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer's NAME on an order, all the way back to the screen.
 *
 * <p>{@code CustomerOfAnOrderIT} guards the link — that the order points at the
 * customer. This guards the thing the order page actually prints, which is the
 * name, and which travels a different road: the association is LAZY, and the DTO
 * reads through it. A null name renders as a dash, and a dash is
 * indistinguishable from "this order is internal" — so an order that HAS a
 * customer and shows a dash is a lie the screen tells with a straight face.
 *
 * <p>Asserted on the way in (create), on the way through (update, both to a
 * different customer and to none), and on the way out (a fresh read, which is
 * the path the order page itself takes).
 */
@Transactional
class OrderCustomerRoundTripIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private CustomerService customerService;
    @Autowired private ProductionOrderService productionOrderService;

    private CustomerDto aCustomer(String name) {
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setName(name + " " + COUNTER.incrementAndGet());
        return customerService.create(request);
    }

    private ProductionOrderDetailDto anOrderFor(Long customerId) {
        int n = COUNTER.incrementAndGet();
        return productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-RT-" + n, "Nalog " + n, customerId,
                null, false, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1), null,
                false, false, false,
                List.of(), List.of(),
                null));
    }

    private ProductionOrderUpdateRequest movingTo(ProductionOrderDetailDto order, Long customerId) {
        return new ProductionOrderUpdateRequest(
                order.name(), customerId, order.note(),
                order.testingRequired(), order.creationDate(), order.orderDate(),
                order.deliveryDeadline(),
                order.isHighPriority(), order.isAnnounced(), order.hasSuccessiveDeliveries(),
                List.of(), List.of());
    }

    @Test
    @DisplayName("an order created for a customer answers with their name, not a blank")
    void createCarriesTheName() {
        CustomerDto customer = aCustomer("Livnica");

        ProductionOrderDetailDto created = anOrderFor(customer.getId());

        assertThat(created.customerId()).isEqualTo(customer.getId());
        assertThat(created.customerName()).isEqualTo(customer.getName());

        // And again on the read the order page actually makes.
        ProductionOrderDetailDto read = productionOrderService.getDetail(created.id());
        assertThat(read.customerId()).isEqualTo(customer.getId());
        assertThat(read.customerName()).isEqualTo(customer.getName());
    }

    @Test
    @DisplayName("moving an order to another customer answers with the NEW name")
    void updateCarriesTheNewName() {
        CustomerDto first = aCustomer("Prvi");
        CustomerDto second = aCustomer("Drugi");

        ProductionOrderDetailDto order = anOrderFor(first.getId());
        ProductionOrderDetailDto moved =
                productionOrderService.update(order.id(), movingTo(order, second.getId()));

        assertThat(moved.customerId()).isEqualTo(second.getId());
        assertThat(moved.customerName()).isEqualTo(second.getName());

        ProductionOrderDetailDto read = productionOrderService.getDetail(order.id());
        assertThat(read.customerId()).isEqualTo(second.getId());
        assertThat(read.customerName()).isEqualTo(second.getName());
    }

    @Test
    @DisplayName("putting a customer on an order that had none answers with their name")
    void updateFromInternalToACustomer() {
        CustomerDto customer = aCustomer("Naknadni");

        ProductionOrderDetailDto internal = anOrderFor(null);
        assertThat(internal.customerName()).isNull();

        ProductionOrderDetailDto given =
                productionOrderService.update(internal.id(), movingTo(internal, customer.getId()));

        assertThat(given.customerId()).isEqualTo(customer.getId());
        assertThat(given.customerName()).isEqualTo(customer.getName());
    }

    @Test
    @DisplayName("clearing the customer really clears it — the dash is then the truth")
    void updateBackToInternal() {
        CustomerDto customer = aCustomer("Bivsi");

        ProductionOrderDetailDto order = anOrderFor(customer.getId());
        ProductionOrderDetailDto cleared =
                productionOrderService.update(order.id(), movingTo(order, null));

        assertThat(cleared.customerId()).isNull();
        assertThat(cleared.customerName()).isNull();
    }
}
