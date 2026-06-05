package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeDeactivationScheduler {

    private final EmployeeRepository employeeRepository;

    /**
     * Svaki dan u 06:30 po srpskom vremenu (Europe/Belgrade).
     * Proverava sve aktivne zaposlene kojima je employment_end_date setovan
     * i manji ili jednak danas — i setuje im active = false.
     */
    @Scheduled(cron = "0 30 6 * * *", zone = "Europe/Belgrade")
    @Transactional
    public void deactivateEmployeesWithExpiredEndDate() {
        LocalDate today = LocalDate.now();

        List<Employee> expired = employeeRepository.findActiveEmployeesWithExpiredEndDate(today);

        if (expired.isEmpty()) {
            log.info("[EmployeeDeactivationScheduler] Nema zaposlenih za deaktivaciju ({}).", today);
            return;
        }

        for (Employee employee : expired) {
            employee.setActive(false);
            log.info("[EmployeeDeactivationScheduler] Deaktiviran zaposleni id={}, employeeNo={}, employmentEndDate={}.",
                    employee.getId(), employee.getEmployeeNo(), employee.getEmploymentEndDate());
        }

        employeeRepository.saveAll(expired);
        log.info("[EmployeeDeactivationScheduler] Ukupno deaktiviranih zaposlenih: {}.", expired.size());
    }
}

