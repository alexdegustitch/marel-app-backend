package com.aleksandarparipovic.marel_app.audit_table;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "audit_tables",
        uniqueConstraints = {
                @UniqueConstraint(name = "audit_tables_table_name_key", columnNames = "table_name")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditTable {

    @Id
    @Column(nullable = false)
    private Short id;

    @Column(name = "table_name", nullable = false)
    private String tableName;
}