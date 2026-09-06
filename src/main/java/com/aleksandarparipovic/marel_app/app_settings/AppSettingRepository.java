package com.aleksandarparipovic.marel_app.app_settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    @Query(value = """
        SELECT s.setting_value_numeric
        FROM app_settings s
        WHERE s.setting_key = 'max_efficiency_percent'
          AND s.is_active = true
          AND s.archived_at IS NULL
          AND s.valid_from <= :at
          AND (s.valid_until IS NULL OR s.valid_until >= :at)
        ORDER BY s.valid_from DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<BigDecimal> findMaxEfficiencyPercentAt(@Param("at") OffsetDateTime at);

    @Query(value = """
        SELECT s.setting_value_numeric
        FROM app_settings s
        WHERE s.setting_key = :key
          AND s.is_active = true
          AND s.archived_at IS NULL
          AND s.valid_from <= :at
          AND (s.valid_until IS NULL OR s.valid_until >= :at)
        ORDER BY s.valid_from DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<BigDecimal> findNumericSettingAt(@Param("key") String key, @Param("at") OffsetDateTime at);

    @Query(value = """
        SELECT DISTINCT ON (s.setting_key) s.*
        FROM app_settings s
        WHERE s.is_active = true
          AND s.archived_at IS NULL
          AND s.valid_from <= :at
          AND (s.valid_until IS NULL OR s.valid_until >= :at)
        ORDER BY s.setting_key, s.valid_from DESC
        """, nativeQuery = true)
    List<AppSetting> findAllCurrentlyValid(@Param("at") OffsetDateTime at);

    @Query(value = """
        SELECT s.*
        FROM app_settings s
        WHERE s.setting_key = :key
          AND s.is_active = true
          AND s.archived_at IS NULL
          AND s.valid_from <= :at
          AND (s.valid_until IS NULL OR s.valid_until >= :at)
        ORDER BY s.valid_from DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AppSetting> findCurrentByKey(@Param("key") String key, @Param("at") OffsetDateTime at);

    @Query(value = """
        SELECT s.*
        FROM app_settings s
        WHERE s.setting_key = :key
          AND s.is_active = true
          AND s.archived_at IS NULL
          AND s.valid_until IS NULL
        ORDER BY s.valid_from DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AppSetting> findOpenEndedByKey(@Param("key") String key);

    List<AppSetting> findByArchivedAtIsNullOrderBySettingKeyAscValidFromDesc();

    @Query(value = """
        SELECT s.*
        FROM app_settings s
        WHERE s.setting_key = :key
          AND s.is_active = true
          AND s.archived_at IS NULL
        ORDER BY s.valid_from ASC
        """, nativeQuery = true)
    List<AppSetting> findAllActiveByKey(@Param("key") String key);

    /**
     * Who inserted each app_settings row, per the audit trail: [record_id, user name].
     * Rows without an insert audit entry (or with a NULL app.user_id, e.g. system
     * writes) are simply absent from the result.
     */
    @Query(value = """
        SELECT al.record_id, COALESCE(u.display_name, u.full_name, u.username)
        FROM audit_logs al
        JOIN audit_tables t ON t.id = al.table_id AND t.table_name = 'app_settings'
        JOIN audit_actions a ON a.id = al.action_id AND a.action_name = 'insert'
        JOIN users u ON u.id = al.user_id
        WHERE al.record_id IN (:ids)
        """, nativeQuery = true)
    List<Object[]> findInsertAuthors(@Param("ids") Collection<Long> ids);
}
