package com.aleksandarparipovic.marel_app.mailing_list;

import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A reusable, named set of recipients owned by the user who created it.
 *
 * <p>Archived (archived_at), never deleted — production orders reference the lists
 * they were built from, and that history must survive.
 */
@Entity
@Table(name = "mailing_lists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailingList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false, updatable = false)
    private User ownerUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private MailingListVisibility visibility = MailingListVisibility.PRIVATE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
