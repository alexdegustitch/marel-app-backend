package com.aleksandarparipovic.marel_app.operation_norm_version;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperationNormVersionRepository extends JpaRepository<OperationNormVersion, Long> {

    /** Newest first — the history as the screen reads it. */
    List<OperationNormVersion> findByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(Long operationId);

    /** The version in force: the most recent one. */
    Optional<OperationNormVersion> findFirstByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(Long operationId);
}
