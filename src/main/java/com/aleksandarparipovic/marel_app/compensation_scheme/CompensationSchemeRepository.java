package com.aleksandarparipovic.marel_app.compensation_scheme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompensationSchemeRepository extends JpaRepository<CompensationScheme, Long> {

    Optional<CompensationScheme> findByCode(String code);

    List<CompensationScheme> findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc();
}
