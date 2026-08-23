package com.aleksandarparipovic.marel_app.operation_norm_version;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * One decision to put a norm in force.
 *
 * <p>Append-only: nothing here is ever updated or deleted, so the chronology is
 * what happened rather than what the record looks like now. An interval — "this
 * norm applied from … to …" — is read from two consecutive entries; for the last
 * one, up to the moment its version was archived.
 *
 * <p>The version's {@code is_current} flag says which norm applies NOW. It is a
 * second statement of the same fact, which is why exactly one method writes both
 * of them, in one transaction: {@code OperationDetailService.makeCurrent}.
 */
@Entity
@Table(name = "operation_norm_activations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationNormActivation {

    /** Which of the four ways in produced the entry. */
    public enum Source {
        /** A new norm was recorded, which puts it in force. */
        ADDED,
        /** The norm in force was edited — same version, new values. */
        EDITED,
        /** It inherited: the norm in force was archived and this one followed it. */
        SUCCEEDED,
        /** Somebody chose an existing norm from the history. */
        ACTIVATED,
        /** Written by the migration for the state it found. */
        MIGRATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "norm_version_id", nullable = false)
    private OperationNormVersion normVersion;

    /*
     * Database-defaulted and read back after the insert, for the same reason
     * created_at is on the version: the row we just wrote would otherwise carry
     * no timestamp for the rest of the session.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "activated_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime activatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activated_by")
    private User activatedBy;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;
}
