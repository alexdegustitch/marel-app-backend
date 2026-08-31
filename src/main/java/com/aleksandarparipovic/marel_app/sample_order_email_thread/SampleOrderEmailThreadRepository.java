package com.aleksandarparipovic.marel_app.sample_order_email_thread;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SampleOrderEmailThreadRepository extends JpaRepository<SampleOrderEmailThread, Long> {

    Optional<SampleOrderEmailThread> findBySampleOrder_Id(Long sampleOrderId);
}
