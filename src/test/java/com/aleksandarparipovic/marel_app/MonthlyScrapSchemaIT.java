package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The monthly scrap table: what it is called, and what it refuses.
 *
 * <p>The table has no entity, repository or endpoint — it is written by hand and
 * read at month end. That is exactly why its rules belong in the database and
 * why they are asserted here: there is no application code to enforce them, and
 * nothing else would notice if a later migration dropped one.
 */
@Transactional
class MonthlyScrapSchemaIT extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("it is called monthly_scraps, and the audit registration followed it")
    void theRenameIsComplete() {
        assertThat(tableExists("monthly_scraps")).isTrue();
        assertThat(tableExists("scraps")).isFalse();

        /*
         * audit_trigger_fn resolves its table by NAME. A registration left behind
         * yields a NULL table_id, which the NOT NULL turns into a failure on
         * every write — so the rename is only finished when this row moved too.
         */
        assertThat(auditRegistrationExists("monthly_scraps")).isTrue();
        assertThat(auditRegistrationExists("scraps")).isFalse();
    }

    @Test
    @DisplayName("its indexes and keys carry the new name too")
    void theNamesAroundItFollowed() {
        assertThat(indexExists("idx_monthly_scraps_period")).isTrue();
        assertThat(indexExists("idx_monthly_scraps_product_id")).isTrue();
        assertThat(constraintExists("fk_monthly_scraps_operation_id")).isTrue();
        assertThat(constraintExists("chk_monthly_scraps_period_month")).isTrue();

        // Postgres keeps them under the old names across a rename unless told.
        assertThat(indexExists("idx_scraps_period")).isFalse();
        assertThat(constraintExists("chk_scraps_period_month")).isFalse();
    }

    @Test
    @DisplayName("a period that is not the first of a month is refused")
    void aMidMonthPeriodIsRefused() {
        var operation = anOperation();

        // Asserted by the constraint's NAME rather than the exception type: a
        // native insert surfaces Hibernate's own wrapper, and the name says which
        // rule refused — which is the thing worth pinning down.
        assertThatThrownBy(() -> insertScrap("2026-08-15", operation.operationId(), operation.productId()))
                .hasMessageContaining("chk_monthly_scraps_period_month");
    }

    @Test
    @DisplayName("the product must be the operation's own")
    void aForeignProductIsRefused() {
        var operation = anOperation();
        Product other = fixture.product("Neki drugi proizvod");

        /*
         * THE POINT OF THE COMPOSITE KEY. operations.product_id is NOT NULL, so
         * the operation already determines the product; storing it again is only
         * safe while the two cannot disagree. Here they are made to disagree, and
         * the database refuses.
         */
        assertThatThrownBy(() -> insertScrap("2026-08-01", operation.operationId(), other.getId()))
                .hasMessageContaining("fk_monthly_scraps_operation_product");
    }

    @Test
    @DisplayName("a scrap for the operation's own product is accepted")
    void theOperationsOwnProductIsAccepted() {
        var operation = anOperation();

        insertScrap("2026-08-01", operation.operationId(), operation.productId());
        entityManager.flush();

        assertThat(scrapCount(operation.operationId())).isEqualTo(1);
    }

    // ─── fixtures and readings ──────────────────────────────────────────────

    private record OperationRef(Long operationId, Long productId) {}

    private OperationRef anOperation() {
        var category = fixture.scenario().build().workCategory();
        var operation = fixture.operation(category, 10);
        entityManager.flush();
        return new OperationRef(operation.getId(), operation.getProduct().getId());
    }

    private void insertScrap(String period, Long operationId, Long productId) {
        entityManager.createNativeQuery("""
                INSERT INTO monthly_scraps (period, operation_id, product_id, quantity, is_active, created_at)
                VALUES (CAST(:period AS date), :operationId, :productId, 3, TRUE, now())""")
                .setParameter("period", period)
                .setParameter("operationId", operationId)
                .setParameter("productId", productId)
                .executeUpdate();
        entityManager.flush();
    }

    private long scrapCount(Long operationId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM monthly_scraps WHERE operation_id = :id")
                .setParameter("id", operationId)
                .getSingleResult()).longValue();
    }

    private boolean tableExists(String name) {
        return !entityManager.createNativeQuery(
                        "SELECT 1 FROM information_schema.tables WHERE table_name = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }

    private boolean auditRegistrationExists(String name) {
        return !entityManager.createNativeQuery(
                        "SELECT 1 FROM audit_tables WHERE table_name = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }

    private boolean indexExists(String name) {
        return !entityManager.createNativeQuery(
                        "SELECT 1 FROM pg_indexes WHERE indexname = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }

    private boolean constraintExists(String name) {
        return !entityManager.createNativeQuery(
                        "SELECT 1 FROM pg_constraint WHERE conname = :name")
                .setParameter("name", name)
                .getResultList().isEmpty();
    }
}
