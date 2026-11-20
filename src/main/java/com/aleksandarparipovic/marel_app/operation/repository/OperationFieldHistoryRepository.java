package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.dto.OperationFieldChangeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The category and units-per-product changes of one operation, read out of
 * {@code operations_history}.
 *
 * <p>That table is written by a database trigger on every operations UPDATE
 * (full old/new row as jsonb), so this repository only has to pick the two
 * attributes out and keep the rows where either actually changed. One history
 * row can carry both changes — the mapper then emits two entries, because the
 * screen lists changes per field, not per UPDATE statement.
 *
 * <p>The category labels are joined here by id. Categories are versioned and a
 * version keeps its name, so the label shows what the category was CALLED when
 * it was assigned — which is the auditable answer.
 */
@Repository
@RequiredArgsConstructor
public class OperationFieldHistoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String SQL = """
            SELECT h.change_time,
                   h.changed_by,
                   u.full_name AS changed_by_name,
                   h.old_data->>'work_code_category_id' AS old_cat_id,
                   h.new_data->>'work_code_category_id' AS new_cat_id,
                   oc.category_no   AS old_cat_no,
                   oc.category_name AS old_cat_name,
                   nc.category_no   AS new_cat_no,
                   nc.category_name AS new_cat_name,
                   h.old_data->>'units_per_product' AS old_units,
                   h.new_data->>'units_per_product' AS new_units
            FROM operations_history h
            LEFT JOIN users u ON u.id = h.changed_by
            LEFT JOIN work_code_categories oc
                   ON oc.id = (h.old_data->>'work_code_category_id')::bigint
            LEFT JOIN work_code_categories nc
                   ON nc.id = (h.new_data->>'work_code_category_id')::bigint
            WHERE h.operation_id = :operationId
              AND (h.old_data->>'work_code_category_id'
                     IS DISTINCT FROM h.new_data->>'work_code_category_id'
                OR h.old_data->>'units_per_product'
                     IS DISTINCT FROM h.new_data->>'units_per_product')
            ORDER BY h.change_time DESC, h.id DESC
            """;

    public List<OperationFieldChangeDto> findFieldChanges(Long operationId) {
        List<OperationFieldChangeDto> out = new ArrayList<>();
        jdbc.query(SQL, new MapSqlParameterSource("operationId", operationId), rs -> {
            OffsetDateTime at = rs.getObject("change_time", OffsetDateTime.class);
            Long byId = rs.getObject("changed_by", Long.class);
            String byName = rs.getString("changed_by_name");

            String oldCatId = rs.getString("old_cat_id");
            String newCatId = rs.getString("new_cat_id");
            if (!equalsNullable(oldCatId, newCatId)) {
                out.add(new OperationFieldChangeDto(
                        OperationFieldChangeDto.FIELD_CATEGORY, at, byId, byName,
                        categoryLabel(rs, "old_cat_no", "old_cat_name", oldCatId),
                        categoryLabel(rs, "new_cat_no", "new_cat_name", newCatId)));
            }

            String oldUnits = rs.getString("old_units");
            String newUnits = rs.getString("new_units");
            if (!equalsNullable(oldUnits, newUnits)) {
                out.add(new OperationFieldChangeDto(
                        OperationFieldChangeDto.FIELD_UNITS, at, byId, byName,
                        oldUnits, newUnits));
            }
        });
        return out;
    }

    /**
     * "B — Mašinska obrada", or the bare id when the category row is gone —
     * an id with no name is still a truer answer than pretending it was empty.
     */
    private static String categoryLabel(ResultSet rs, String noColumn, String nameColumn, String id)
            throws SQLException {
        if (id == null) return null;
        String no = rs.getString(noColumn);
        String name = rs.getString(nameColumn);
        if (no == null && name == null) return "#" + id;
        if (name == null) return no;
        return no + " — " + name;
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
