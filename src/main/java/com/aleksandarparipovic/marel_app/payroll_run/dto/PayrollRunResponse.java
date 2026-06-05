package com.aleksandarparipovic.marel_app.payroll_run.dto;

import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
public class PayrollRunResponse {

    private final Long id;
    private final Integer reportYear;
    private final Integer reportMonth;
    private final String runCode;
    private final String status;
    private final String note;
    private final Long createdById;
    private final Long approvedById;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime approvedAt;
    private final OffsetDateTime lockedAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public PayrollRunResponse(PayrollRun run) {
        this.id = run.getId();
        this.reportYear = run.getReportYear();
        this.reportMonth = run.getReportMonth();
        this.runCode = run.getRunCode();
        this.status = run.getStatus();
        this.note = run.getNote();
        this.createdById = run.getCreatedBy() != null ? run.getCreatedBy().getId() : null;
        this.approvedById = run.getApprovedBy() != null ? run.getApprovedBy().getId() : null;
        this.createdAt = run.getCreatedAt();
        this.approvedAt = run.getApprovedAt();
        this.lockedAt = run.getLockedAt();
        this.updatedAt = run.getUpdatedAt();
        this.archivedAt = run.getArchivedAt();
    }
}

