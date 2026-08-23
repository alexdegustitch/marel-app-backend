package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the invariants which must NOT depend on application code actually
 * exist in the database.
 *
 * <p>Every rule here is one that application validation alone could not guarantee
 * under concurrency. If a future migration drops one, this fails.
 */
class SchemaConstraintsIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    private boolean constraintExists(String name) {
        return !entityManager.createNativeQuery(
                        "select 1 from pg_constraint where conname = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }

    private boolean indexExists(String name) {
        return !entityManager.createNativeQuery(
                        "select 1 from pg_indexes where indexname = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }

    @Test
    @DisplayName("workflow state coherence is enforced by check constraints")
    void checkConstraintsExist() {
        assertThat(constraintExists("chk_users_account_status")).isTrue();
        assertThat(constraintExists("chk_user_registration_requests_review_state")).isTrue();
        assertThat(constraintExists("chk_manufacturing_time_requests_processing_state")).isTrue();
        assertThat(constraintExists("chk_manufacturing_time_requests_assignment_state")).isTrue();
        assertThat(constraintExists("chk_manufacturing_time_requests_target_required")).isTrue();
        // A request's order line and its product are one pair, checked as one key,
        // so the two can never name different products.
        assertThat(constraintExists("fk_manufacturing_time_requests_line_item")).isTrue();
        // A finished request always has a result; an unfinished or refused one
        // never does.
        assertThat(constraintExists("chk_manufacturing_time_requests_result_state")).isTrue();
        // The answering record is about the product the request is about.
        assertThat(constraintExists("fk_manufacturing_time_requests_result")).isTrue();
        assertThat(constraintExists("chk_mailing_list_members_exactly_one_source")).isTrue();
        assertThat(constraintExists("chk_po_recipients_source_list_consistency")).isTrue();
        assertThat(constraintExists("chk_po_recipients_removal_state")).isTrue();
        assertThat(constraintExists("chk_notification_deliveries_target")).isTrue();
        assertThat(constraintExists("chk_outbox_events_processed_at")).isTrue();
    }

    @Test
    @DisplayName("uniqueness that protects against races is enforced by partial indexes")
    void uniqueIndexesExist() {
        // One open registration request per user.
        assertThat(indexExists("uq_user_registration_requests_one_pending")).isTrue();
        // One manufacturing-time result per request.
        assertThat(indexExists("uq_pmt_source_request_id")).isTrue();
        // One active recipient per address per production order — the dedup guarantee.
        assertThat(indexExists("uq_po_recipients_order_email_active")).isTrue();
        // One notification event per outbox row — the outbox idempotency backbone.
        assertThat(indexExists("uq_notification_events_outbox_event_id")).isTrue();
        // One notification per user per event.
        assertThat(indexExists("uq_user_notifications_event_user")).isTrue();
        // One email per address per event.
        assertThat(indexExists("uq_notification_deliveries_email")).isTrue();
        // One active default saved view per user per screen.
        assertThat(indexExists("uq_user_saved_views_one_default")).isTrue();
        // Case-insensitive member uniqueness.
        assertThat(indexExists("uq_mailing_list_members_email_active")).isTrue();
    }

    @Test
    @DisplayName("retired stub tables are gone and their audit history is preserved")
    void legacyStubsRetired() {
        assertThat(entityManager.createNativeQuery(
                        "select 1 from pg_tables where tablename in ('requests','notifications')")
                .getResultList()).isEmpty();

        assertThat(entityManager.createNativeQuery(
                        "select table_name from audit_tables where table_name like '%_legacy'")
                .getResultList()).hasSize(3);
    }
}
