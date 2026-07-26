package com.aleksandarparipovic.marel_app.analytics;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeEfficiencyDto;
import com.aleksandarparipovic.marel_app.analytics.dto.OperationEfficiencyDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductDateOperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryRepository queryRepo;

    // Page 1 — Proizvod-operacija
    @PostMapping("/product-operation")
    public List<ProductOperationSummaryDto> productOperation(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findProductOperationSummary(filter);
    }

    // Page 4 — Efikasnost proizvoda. Same query/output shape as page 1 (identical spec),
    // exposed as its own endpoint for a clearer frontend route per page.
    @PostMapping("/product-efficiency")
    public List<ProductOperationSummaryDto> productEfficiency(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findProductOperationSummary(filter);
    }

    // Page 2 — Proizvod-datum-operacija-radnik
    @PostMapping("/product-date-operation-employee")
    public List<ProductDateOperationEmployeeDto> productDateOperationEmployee(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findProductDateOperationEmployeeSummary(filter);
    }

    // Page 3 — Efikasnost radnika
    @PostMapping("/employee-efficiency")
    public List<EmployeeEfficiencyDto> employeeEfficiency(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findEmployeeEfficiency(filter);
    }

    // Page 5 — Efikasnost operacija - količina
    @PostMapping("/operation-efficiency")
    public List<OperationEfficiencyDto> operationEfficiency(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findOperationEfficiency(filter);
    }

    // Backs the "napomena" multi-select filter option list on all 5 analytics pages.
    @GetMapping("/filters/notes")
    public List<String> distinctNotes() {
        return queryRepo.findDistinctNotes();
    }

    // Backs the product/operation/employee multi-select filter option lists.
    @GetMapping("/filters/products")
    public List<AnalyticsOptionDto> distinctProducts() {
        return queryRepo.findDistinctProducts();
    }

    @GetMapping("/filters/operations")
    public List<AnalyticsOptionDto> distinctOperations() {
        return queryRepo.findDistinctOperations();
    }

    @GetMapping("/filters/employees")
    public List<AnalyticsOptionDto> distinctEmployees() {
        return queryRepo.findDistinctEmployees();
    }
}
