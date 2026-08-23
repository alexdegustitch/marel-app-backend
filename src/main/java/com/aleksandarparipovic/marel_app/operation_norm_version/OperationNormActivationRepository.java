package com.aleksandarparipovic.marel_app.operation_norm_version;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperationNormActivationRepository extends JpaRepository<OperationNormActivation, Long> {

    /** The chronology, newest decision first. */
    List<OperationNormActivation> findByOperation_IdOrderByActivatedAtDescIdDesc(Long operationId);
}
