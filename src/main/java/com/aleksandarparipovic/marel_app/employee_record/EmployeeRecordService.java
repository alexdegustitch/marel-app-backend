package com.aleksandarparipovic.marel_app.employee_record;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordCreateResponse;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordDto;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordEmployeeInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.RecentEmployeeRecordDto;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.payroll_run.event.PayrollMonthInitEvent;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeRecordService {

    private final EmployeeRecordRepository employeeRecordRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserService currentUserService;
    private final ApplicationEventPublisher eventPublisher;

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

        try {
            return employeeRecordRepository.save(record);
        } catch (DataIntegrityViolationException ex) {
            return employeeRecordRepository.findByEmployeeIdAndStartDate(employeeId, monthStart)
                    .orElseThrow(() -> ex);
        }
    }

    public List<EmployeeRecordDto> findLastThreePerMonthForSupervisor(int year, int month){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);
        Long userId = currentUserService.getCurrentUserId();
        return employeeRecordRepository.findLastThreePerMonthForSupervisor(userId, start, end);
    }

    @Transactional(readOnly = true)
    public EmployeeRecordEmployeeInfo getByEmployeeRecordId(Long id) {
        return employeeRecordRepository.findDtoById(id)
                .orElseThrow(() -> new EntityNotFoundException("Employee record not found: " + id));
    }

    @Transactional(readOnly = true)
    public boolean existsForEmployeeAndMonth(Long employeeId, int year, int month) {
        LocalDate startDate = YearMonth.of(year, month).atDay(1);
        return employeeRecordRepository.existsByEmployeeIdAndStartDate(employeeId, startDate);
    }

    @Transactional(readOnly = true)
    public List<RecentEmployeeRecordDto> getRecentByEmployeeId(Long employeeId, int size) {
        return employeeRecordRepository.findRecentByEmployeeId(employeeId, size);
    }

    public Page<EmployeeRecordInfo> getEmployeeRecordsByYearAndMonth(int year, int month, String globalSearch, Pageable pageable){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);

        return employeeRecordRepository.findMonthlyRecords(start, end, globalSearch, pageable);
    }

    @Transactional
    public EmployeeRecordCreateResponse createEmployeeRecordsForMonth(int year, int month) {
        LocalDate monthStart = YearMonth.of(year, month).atDay(1);
        int created = 0;
        List<Long> employeeRecordIds = new ArrayList<>();
        List<Long> employeeIds = new ArrayList<>();

        List<Employee> employees = employeeRepository.findAllByActiveTrueAndArchivedAtIsNull();
        for (Employee employee : employees) {
            if (employeeRecordRepository.findByEmployeeIdAndStartDate(employee.getId(), monthStart).isPresent()) {
                continue;
            }

            EmployeeRecord createdRecord = createMonthlyRecord(employee.getId(), monthStart);
            employeeRecordIds.add(createdRecord.getId());
            employeeIds.add(employee.getId());
            created++;
        }

        if (!employeeRecordIds.isEmpty()) {
            publishInitEvent(year, month, employeeRecordIds);
        }

        return new EmployeeRecordCreateResponse(year, month, created, employeeRecordIds, employeeIds);
    }

    private void publishInitEvent(int year, int month, List<Long> employeeRecordIds) {
        Long userId = currentUserService.getCurrentUserId();
        eventPublisher.publishEvent(new PayrollMonthInitEvent(year, month, employeeRecordIds, userId));
    }
}
