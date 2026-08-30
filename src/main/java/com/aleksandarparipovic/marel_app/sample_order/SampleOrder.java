package com.aleksandarparipovic.marel_app.sample_order;

import com.aleksandarparipovic.marel_app.customer.Customer;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sample_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * The customer these samples are for, when they are for one.
     *
     * <p>Null is a correct answer and not a gap: samples made for an internal
     * trial are for nobody outside.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /**
     * The šifra people quote the order by. Immutable once created, exactly as a
     * production order's is: it is how the order is referred to in conversation
     * and on paper, and a code that moves is a code nobody can rely on.
     */
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "creation_date", nullable = false)
    private LocalDate creationDate;

    /**
     * The rok. ONE date, unlike a production order's list of delivery windows —
     * samples go out in one go, so a second window would be describing something
     * that does not happen.
     *
     * <p>The database also requires {@code creationDate <= deadlineDate}
     * ({@code chk_sample_orders_valid_deadline}); the service refuses the pair in
     * words before it gets that far.
     */
    @Column(name = "deadline_date", nullable = false)
    private LocalDate deadlineDate;

    /**
     * The rok in words — "po dogovoru", "kraj februara". Beside
     * {@link #deadlineDate} rather than instead of it: the date is what the list
     * sorts and warns on, this is what a person reads.
     */
    @Column(name = "deadline_note")
    private String deadlineNote;

    /** A napomena about the WHOLE order, as opposed to the one on each line. */
    @Column(name = "note")
    private String note;

    /**
     * Free text on the database side ({@code varchar default 'created'}), so the
     * values live in {@link SampleOrderStatus} as constants rather than as an
     * enum. Mapping it as an enum would write "CREATED" over a column whose
     * existing rows — and the product page that already reads them — say
     * "created".
     */
    @Column(name = "status", nullable = false)
    private String status = SampleOrderStatus.CREATED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
