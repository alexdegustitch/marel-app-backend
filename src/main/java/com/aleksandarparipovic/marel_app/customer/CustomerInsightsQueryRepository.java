package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.customer.dto.CustomerTopProductRow;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * The read-only questions the customer pages ask about ORDERS — how many, how
 * many still open, what gets ordered most. Its own repository rather than more
 * methods on {@link CustomerRepository}, the way order progress keeps its
 * queries in a query repository: these aggregate other tables and store
 * nothing.
 *
 * <p>Everything here counts live (non-archived) orders only, so the figures
 * agree with what the order lists under them show.
 */
public interface CustomerInsightsQueryRepository extends Repository<Customer, Long> {

    /** Production-order figures for a page of customers, one grouped query for the page. */
    @Query("""
        select o.customer.id as customerId,
               count(o) as total,
               sum(case when o.status = :active then 1 else 0 end) as active,
               max(o.orderDate) as lastOrderDate
        from ProductionOrder o
        where o.customer.id in :customerIds
          and o.archivedAt is null
        group by o.customer.id
        """)
    List<ProductionOrderCounts> productionOrderCounts(
            @Param("customerIds") Collection<Long> customerIds,
            @Param("active") ProductionOrderStatus active);

    /** Sample-order figures for a page of customers. Open means not yet closed. */
    @Query("""
        select s.customer.id as customerId,
               count(s) as total,
               sum(case when s.status is null or lower(s.status) <> 'closed' then 1 else 0 end) as open
        from SampleOrder s
        where s.customer.id in :customerIds
          and s.archivedAt is null
        group by s.customer.id
        """)
    List<SampleOrderCounts> sampleOrderCounts(@Param("customerIds") Collection<Long> customerIds);

    /** Active customers with at least one live production order still in {@code active}. */
    @Query("""
        select count(c) from Customer c
        where c.isActive = true
          and exists (
              select 1 from ProductionOrder o
              where o.customer = c
                and o.archivedAt is null
                and o.status = :active)
        """)
    long countCustomersWithActiveOrders(@Param("active") ProductionOrderStatus active);

    /**
     * What this customer orders most, summed from the live lines of their live
     * production orders. The caller caps the page — the panel shows a handful,
     * not the catalogue.
     */
    @Query("""
        select new com.aleksandarparipovic.marel_app.customer.dto.CustomerTopProductRow(
               p.id, p.productName, p.productCode,
               sum(li.quantity), count(distinct o.id), max(o.orderDate))
        from ProductionOrderLineItem li
        join li.productionOrder o
        join li.product p
        where o.customer.id = :customerId
          and o.archivedAt is null
          and li.isActive = true
          and li.archivedAt is null
        group by p.id, p.productName, p.productCode
        order by sum(li.quantity) desc, max(o.orderDate) desc, p.id asc
        """)
    List<CustomerTopProductRow> topProducts(@Param("customerId") Long customerId, Pageable pageable);

    interface ProductionOrderCounts {
        Long getCustomerId();
        long getTotal();
        long getActive();
        LocalDate getLastOrderDate();
    }

    interface SampleOrderCounts {
        Long getCustomerId();
        long getTotal();
        long getOpen();
    }
}
