package com.aleksandarparipovic.marel_app.app_settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
}
