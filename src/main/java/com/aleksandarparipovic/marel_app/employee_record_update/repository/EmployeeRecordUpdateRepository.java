package com.aleksandarparipovic.marel_app.employee_record_update.repository;

import com.aleksandarparipovic.marel_app.employee_record_update.EmployeeRecordUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeRecordUpdateRepository extends JpaRepository<EmployeeRecordUpdate, Long> {
}

