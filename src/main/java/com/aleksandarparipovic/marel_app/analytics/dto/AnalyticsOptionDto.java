package com.aleksandarparipovic.marel_app.analytics.dto;

// Generic (id, label) option pair backing the product/operation/employee multi-select filter
// lists on the analytics pages. Values are drawn from work_log_facts (only dimension values
// that actually occur in the data), not the full products/operations/employees tables.
public record AnalyticsOptionDto(Long id, String label) {
}
