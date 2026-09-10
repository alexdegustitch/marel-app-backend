package com.aleksandarparipovic.marel_app.role;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;


@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_name", nullable = false, unique = true)
    private String roleName;

    /**
     * The name shown to people, when the company has given the role one.
     *
     * <p>{@code role_name} is the identifier authorization compares against;
     * this is the word a screen prints instead. Nullable on purpose — a role
     * without one reads as its identifier rather than as a blank.
     */
    @Column(name = "display_name")
    private String displayName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

}
