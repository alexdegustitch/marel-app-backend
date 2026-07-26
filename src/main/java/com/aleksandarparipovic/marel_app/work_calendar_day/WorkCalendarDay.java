package com.aleksandarparipovic.marel_app.work_calendar_day;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "work_calendar_days",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_work_calendar_days_calendar_date",
                columnNames = {"calendar_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkCalendarDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_date", nullable = false)
    private LocalDate calendarDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 30)
    private WorkCalendarDayType dayType;

    // Holiday/leave name shown in the UI, e.g. "Dan državnosti"
    @Column(name = "label")
    private String label;

    // Manual override of the effective working status: true = force working
    // regardless of dayType (e.g. a worked holiday), false = force non-working
    // regardless of dayType (e.g. an unworked Saturday), null = use dayType default.
    @Column(name = "working_override")
    private Boolean workingOverride;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
