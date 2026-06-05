package com.aleksandarparipovic.marel_app.employee_record;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class EmployeeRecordService {

    private final EmployeeRecordRepository employeeRecordRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public EmployeeRecord getOrCreateMonthlyRecord(Long employeeId, LocalDate workDate) {
        LocalDate monthStart = YearMonth.from(workDate).atDay(1);

        return employeeRecordRepository.findByEmployeeIdAndStartDate(employeeId, monthStart)
                .orElseGet(() -> createMonthlyRecord(employeeId, monthStart));
    }

    private EmployeeRecord createMonthlyRecord(Long employeeId, LocalDate monthStart) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found: " + employeeId));

        EmployeeRecord record = EmployeeRecord.builder()
                .employee(employee)
                .startDate(monthStart)
                .active(Boolean.TRUE)
                .build();

        return employeeRecordRepository.save(record);
    }
}

