package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "report_year", nullable = false)
	private Integer reportYear;

	@Column(name = "report_month", nullable = false)
	private Integer reportMonth;

	@Column(name = "run_code", nullable = false)
	private String runCode;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "note")
	private String note;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by")
	private User createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approved_by")
	private User approvedBy;

	@Column(name = "created_at", updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "approved_at")
	private OffsetDateTime approvedAt;

	@Column(name = "locked_at")
	private OffsetDateTime lockedAt;

	@Column(name = "updated_at")
	private OffsetDateTime updatedAt;

	@Column(name = "archived_at")
	private OffsetDateTime archivedAt;
}

