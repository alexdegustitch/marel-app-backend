package com.aleksandarparipovic.marel_app.sample_order.repository;

import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SampleOrderRepository extends JpaRepository<SampleOrder, Long>, JpaSpecificationExecutor<SampleOrder> {

    List<SampleOrder> findByIsActiveIsTrueOrderByNameAsc();
}
