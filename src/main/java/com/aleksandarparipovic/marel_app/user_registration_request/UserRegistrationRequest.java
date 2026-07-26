package com.aleksandarparipovic.marel_app.user_registration_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * The administrator's decision record for one self-registration.
 *
 * <p>Rows are never deleted after review. Status is only ever changed through the
 * {@code approve}/{@code decline}/{@code cancel} methods below — there is
 * deliberately no public status setter, so no caller can put the row into a state
 * the database check constraints would reject or the workflow does not allow.
 */
@Entity
@Table(name = "user_registration_requests")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRegistrationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UserRegistrationRequestStatus status = UserRegistrationRequestStatus.PENDING;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    /** The administrator who decided. Current business state, not audit metadata. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * Guards against two administrators reviewing the same pending request. The
     * second flush fails with an OptimisticLockingFailureException, surfaced as
     * HTTP 409, instead of one decision silently overwriting the other.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void approve(User reviewer, String note) {
        transitionTo(UserRegistrationRequestStatus.APPROVED, reviewer, note);
    }

    public void decline(User reviewer, String note) {
        transitionTo(UserRegistrationRequestStatus.DECLINED, reviewer, note);
    }

    public void cancel(User actor, String note) {
        requireOpen(UserRegistrationRequestStatus.CANCELLED);
        this.status = UserRegistrationRequestStatus.CANCELLED;
        this.reviewedBy = actor;
        this.reviewedAt = OffsetDateTime.now();
        this.reviewNote = normalizeNote(note);
    }

    private void transitionTo(UserRegistrationRequestStatus target, User reviewer, String note) {
        requireOpen(target);
        if (reviewer == null) {
            throw new IllegalArgumentException("Reviewer is required");
        }
        this.status = target;
        this.reviewedBy = reviewer;
        this.reviewedAt = OffsetDateTime.now();
        this.reviewNote = normalizeNote(note);
    }

    private void requireOpen(UserRegistrationRequestStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Zahtev je već obrađen (" + status + ") i ne može ponovo da se menja."
            );
        }
    }

    /** Blank is stored as NULL — the database rejects a whitespace-only note. */
    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }
}
