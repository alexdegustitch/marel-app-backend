package com.aleksandarparipovic.marel_app.dashboard.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the daily snapshot.
 *
 * <p>Plain JDBC rather than an entity: the payload is a JSON document whose shape
 * differs per key, and mapping that through JPA would buy nothing — nothing joins
 * to this table, nothing cascades from it, and it is written by exactly one job.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DashboardInsightRepository {

    /**
     * Its own mapper, not the context's.
     *
     * <p>Spring Boot 4 auto-configures a Jackson 3 mapper for HTTP; the payload
     * here is written and read with Jackson 2, the same generation the project's
     * other JSONB columns use. Dates are written as ISO strings rather than as
     * numbers, so a payload stored today still reads the same after any change to
     * a serialisation default.
     */
    private static final ObjectMapper PAYLOAD_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final String UPSERT_SQL = """
            INSERT INTO dashboard_insights (insight_key, computed_for, window_days, payload, computed_at)
            VALUES (:key, :computedFor, :windowDays, cast(:payload AS jsonb), now())
            ON CONFLICT (insight_key, computed_for) DO UPDATE SET
                window_days = EXCLUDED.window_days,
                payload     = EXCLUDED.payload,
                computed_at = now()
            """;

    private static final String LATEST_SQL = """
            SELECT computed_for, computed_at, window_days, payload::text AS payload
            FROM dashboard_insights
            WHERE insight_key = :key
            ORDER BY computed_for DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** One insight as it was stored, with the day it describes. */
    public record Stored<T>(
            LocalDate computedFor,
            OffsetDateTime computedAt,
            Integer windowDays,
            List<T> rows
    ) {}

    public void save(DashboardInsightKey key, LocalDate computedFor, Integer windowDays, List<?> rows) {
        String payload;
        try {
            payload = PAYLOAD_MAPPER.writeValueAsString(rows == null ? List.of() : rows);
        } catch (JsonProcessingException e) {
            // Serialising our own records cannot realistically fail; if it ever does,
            // the day's snapshot is what is lost, not the caller.
            throw new IllegalStateException("Insight payload could not be serialised: " + key, e);
        }

        jdbc.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("key", key.name())
                .addValue("computedFor", computedFor)
                .addValue("windowDays", windowDays)
                .addValue("payload", payload));
    }

    /**
     * The newest stored answer for one key, whatever day it is from.
     *
     * <p>Deliberately not "today's": if the job did not run this morning, the board
     * shows yesterday's figures WITH yesterday's date on them rather than an empty
     * screen. Stale and labelled beats blank and unexplained.
     */
    public <T> Optional<Stored<T>> findLatest(DashboardInsightKey key, Class<T> rowType) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    LATEST_SQL,
                    new MapSqlParameterSource("key", key.name()),
                    (rs, rowNum) -> new Stored<>(
                            rs.getObject("computed_for", LocalDate.class),
                            rs.getObject("computed_at", OffsetDateTime.class),
                            (Integer) rs.getObject("window_days"),
                            readRows(rs.getString("payload"), rowType, key))));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Keeps the table to a few months; the trend never looks further back than that. */
    public int deleteComputedBefore(LocalDate cutoff) {
        return jdbc.update(
                "DELETE FROM dashboard_insights WHERE computed_for < :cutoff",
                new MapSqlParameterSource("cutoff", cutoff));
    }

    private <T> List<T> readRows(String payload, Class<T> rowType, DashboardInsightKey key) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return PAYLOAD_MAPPER.readValue(
                    payload,
                    PAYLOAD_MAPPER.getTypeFactory().constructCollectionType(List.class, rowType));
        } catch (JsonProcessingException e) {
            // A payload written by an older shape of the row. One stale card is a
            // far better outcome than a control board that will not open at all.
            log.warn("[DashboardInsight] Payload for {} could not be read; showing it as empty.", key, e);
            return List.of();
        }
    }
}
