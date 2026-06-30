package com.aleksandarparipovic.marel_app.monthly_report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long>, JpaSpecificationExecutor<MonthlyReport> {

    Optional<MonthlyReport> findByEmployeeRecord_Id(Long employeeRecordId);

    @Query("""
            select mr
            from MonthlyReport mr
            where mr.employeeRecord.employee.id = :employeeId
              and mr.employeeRecord.startDate = :employeeRecordStartDate
            """)
    Optional<MonthlyReport> findByEmployeeIdAndEmployeeRecordStartDate(
            @Param("employeeId") Long employeeId,
            @Param("employeeRecordStartDate") LocalDate employeeRecordStartDate
    );

    List<MonthlyReport> findAllByEmployeeRecord_Employee_IdAndStartDateAndEndDate(
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<MonthlyReport> findByEmployeeRecord_IdIn(Collection<Long> employeeRecordIds);

    @Query("""
            SELECT mr FROM MonthlyReport mr
            JOIN FETCH mr.employeeRecord er
            JOIN FETCH er.employee
            WHERE er.id IN :ids
            """)
    List<MonthlyReport> findByEmployeeRecord_IdInWithEmployee(@Param("ids") Collection<Long> ids);
}
