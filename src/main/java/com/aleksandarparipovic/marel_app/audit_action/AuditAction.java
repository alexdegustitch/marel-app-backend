package com.aleksandarparipovic.marel_app.audit_action;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "audit_actions",
        uniqueConstraints = {
                @UniqueConstraint(name = "audit_actions_action_name_key", columnNames = "action_name")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditAction {

    @Id
    @Column(nullable = false)
    private Short id;

    @Column(name = "action_name", nullable = false)
    private String actionName;
}