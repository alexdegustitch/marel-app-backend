package com.aleksandarparipovic.marel_app.employee_payroll_run_item_update;

import com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.dto.EmployeePayrollRunItemUpdateDto;
import com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.repository.EmployeePayrollRunItemUpdateRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeePayrollRunItemUpdateService {

    private final EmployeePayrollRunItemUpdateRepository repository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final UserRepository userRepository;

    @Transactional
    public void upsertActivity(Long payrollRunItemId, Long userId) {
        PayrollRunItem item = payrollRunItemRepository.findById(payrollRunItemId)
                .orElseThrow(() -> new EntityNotFoundException("PayrollRunItem not found: " + payrollRunItemId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        repository.findByPayrollRunItemIdOrderByLastActivityAtDesc(payrollRunItemId)
                .stream()
                .filter(u -> u.getUser().getId().equals(userId))
                .findFirst()
                .ifPresentOrElse(
                        existing -> {
                            existing.setLastActivityAt(OffsetDateTime.now());
                            repository.save(existing);
                        },
                        () -> repository.save(EmployeePayrollRunItemUpdate.builder()
                                .payrollRunItem(item)
                                .user(user)
                                .lastActivityAt(OffsetDateTime.now())
                                .build())
                );
    }

    @Transactional(readOnly = true)
    public List<EmployeePayrollRunItemUpdateDto> getByPayrollRunItemId(Long payrollRunItemId) {
        return repository.findByPayrollRunItemIdOrderByLastActivityAtDesc(payrollRunItemId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeePayrollRunItemUpdateDto> getByUserId(Long userId) {
        return repository.findByUserIdOrderByLastActivityAtDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private EmployeePayrollRunItemUpdateDto toDto(EmployeePayrollRunItemUpdate e) {
        String userName = e.getUser().getFullName();
        return new EmployeePayrollRunItemUpdateDto(
                e.getId(),
                e.getPayrollRunItem().getId(),
                e.getUser().getId(),
                userName,
                e.getLastActivityAt()
        );
    }
}


