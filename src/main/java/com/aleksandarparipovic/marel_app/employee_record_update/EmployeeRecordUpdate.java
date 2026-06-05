package com.aleksandarparipovic.marel_app.employee_record_update;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "employee_record_updates",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_employee_record_updates_record_user",
                columnNames = {"employee_record_id", "user_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeRecordUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_record_id", nullable = false)
    private EmployeeRecord employeeRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;
}

