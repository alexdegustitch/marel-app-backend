package com.aleksandarparipovic.marel_app.search;

import com.aleksandarparipovic.marel_app.customer.CustomerRepository;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.search.dto.EmployeeSearchRow;
import com.aleksandarparipovic.marel_app.search.dto.SearchResult;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one query behind the command palette: resolve a typed fragment to
 * deep-linkable entities across the app.
 *
 * <p>Read-only. Every branch is a capped ILIKE over an existing table; nothing
 * here reaches a payroll amount, so every authenticated user may call it. The
 * client has already stripped type/month/year words from {@code q}, so a blank
 * {@code q} is legitimate and means "the top of each requested type".
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    public static final String TYPE_EMPLOYEE = "employee";
    public static final String TYPE_USER = "user";
    public static final String TYPE_PRODUCT = "product";
    public static final String TYPE_OPERATION = "operation";
    public static final String TYPE_ORDER = "order";
    public static final String TYPE_CUSTOMER = "customer";
    public static final String TYPE_SAMPLE_ORDER = "sampleOrder";
    public static final String TYPE_EMPLOYEE_RECORD = "employeeRecord";

    /** Upper bound on results per type, regardless of the requested limit. */
    private static final int MAX_LIMIT = 25;

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OperationRepository operationRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final CustomerRepository customerRepository;
    private final SampleOrderRepository sampleOrderRepository;
    private final EmployeeRecordRepository employeeRecordRepository;

    @Transactional(readOnly = true)
    public List<SearchResult> search(String q, Integer month, Integer year, String types, int limit) {

        String needle = q == null ? "" : q.trim();
        // Default 8; callers may ask for more (the palette asks ~10), clamped to
        // a sane ceiling so a stray large limit can't sweep a whole table.
        int cap = limit <= 0 ? 8 : Math.min(limit, MAX_LIMIT);
        Pageable page = PageRequest.of(0, cap);
        Set<String> wanted = parseTypes(types);

        List<SearchResult> results = new ArrayList<>();

        if (wants(wanted, TYPE_EMPLOYEE)) {
            for (EmployeeSearchRow e : employeeRepository.searchTop(needle, page)) {
                results.add(new SearchResult(
                        TYPE_EMPLOYEE,
                        e.getId(),
                        e.getFullName(),
                        joinSubtitle(e.getEmployeeNo(), e.getDepartmentName()),
                        "/app/employees/" + e.getId()));
            }
        }

        if (wants(wanted, TYPE_USER)) {
            userRepository.searchTop(needle, page).forEach(u -> results.add(new SearchResult(
                    TYPE_USER,
                    u.getId(),
                    StringUtils.hasText(u.getDisplayName()) ? u.getDisplayName() : u.getFullName(),
                    StringUtils.hasText(u.getUsername()) ? u.getUsername() : u.getRoleName(),
                    "/app/users/" + u.getId())));
        }

        if (wants(wanted, TYPE_PRODUCT)) {
            productRepository.searchTop(needle, page).forEach(p -> results.add(new SearchResult(
                    TYPE_PRODUCT,
                    p.getId(),
                    p.getProductName(),
                    p.getProductCode(),
                    "/app/products/" + p.getId())));
        }

        if (wants(wanted, TYPE_OPERATION)) {
            operationRepository.searchTop(needle, page).forEach(o -> results.add(new SearchResult(
                    TYPE_OPERATION,
                    o.getId(),
                    o.getOpName(),
                    o.getProductName(),
                    "/app/operations/" + o.getId())));
        }

        if (wants(wanted, TYPE_ORDER)) {
            productionOrderRepository.searchTop(needle, page).forEach(o -> results.add(new SearchResult(
                    TYPE_ORDER,
                    o.getId(),
                    o.getName(),
                    joinSubtitle(o.getCode(), o.getCustomerName()),
                    "/app/production-orders/" + o.getId())));
        }

        if (wants(wanted, TYPE_CUSTOMER)) {
            customerRepository.searchTop(needle, page).forEach(c -> results.add(new SearchResult(
                    TYPE_CUSTOMER,
                    c.getId(),
                    c.getName(),
                    StringUtils.hasText(c.getCode()) ? c.getCode() : c.getTaxId(),
                    "/app/customers/" + c.getId())));
        }

        if (wants(wanted, TYPE_SAMPLE_ORDER)) {
            sampleOrderRepository.searchTop(needle, page).forEach(s -> results.add(new SearchResult(
                    TYPE_SAMPLE_ORDER,
                    s.getId(),
                    s.getName(),
                    joinSubtitle(s.getCode(), s.getCustomerName()),
                    "/app/sample-orders/" + s.getId())));
        }

        // Karton: only meaningful with a concrete month + year to resolve against.
        if (wants(wanted, TYPE_EMPLOYEE_RECORD) && month != null && year != null) {
            LocalDate monthStart = LocalDate.of(year, month, 1);
            // Cap the employee lookup to the first `cap` matches to avoid N+1 blow-up.
            for (EmployeeSearchRow e : employeeRepository.searchTop(needle, page)) {
                employeeRecordRepository.findByEmployeeIdAndStartDate(e.getId(), monthStart)
                        .ifPresent(record -> results.add(new SearchResult(
                                TYPE_EMPLOYEE_RECORD,
                                record.getId(),
                                e.getFullName(),
                                "Karton " + month + "/" + year,
                                "/app/employee-records?employeeRecordId=" + record.getId())));
            }
        }

        return results;
    }

    /** Absent/blank means all types; otherwise the CSV set to restrict to. */
    private Set<String> parseTypes(String types) {
        if (!StringUtils.hasText(types)) {
            return Set.of();
        }
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private boolean wants(Set<String> wanted, String type) {
        return wanted.isEmpty() || wanted.contains(type);
    }

    /** "A - B" when both are present, otherwise whichever one is. */
    private String joinSubtitle(String primary, String secondary) {
        boolean hasPrimary = StringUtils.hasText(primary);
        boolean hasSecondary = StringUtils.hasText(secondary);
        if (hasPrimary && hasSecondary) {
            return primary + " - " + secondary;
        }
        if (hasPrimary) {
            return primary;
        }
        return hasSecondary ? secondary : null;
    }
}
