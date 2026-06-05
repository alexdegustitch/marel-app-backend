package com.aleksandarparipovic.marel_app.employee_record.repository;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRecordRepository extends JpaRepository<EmployeeRecord, Long> {

    Optional<EmployeeRecord> findTopByEmployeeIdAndActiveTrueOrderByStartDateDesc(Long employeeId);
}

