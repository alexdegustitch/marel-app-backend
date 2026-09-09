package com.aleksandarparipovic.marel_app.assistant;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeProductOperationDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NormBasisDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NoteOccurrenceDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sparky's function-calling tools: the JSON-schema definitions the model sees,
 * and the read-only implementations behind them.
 *
 * <p>Each tool resolves the free-text names in its arguments to ids on the
 * backend (via the existing {@code searchTop} finders / shift list), builds an
 * {@link AnalyticsFilterRequest}, runs one real query through
 * {@link AnalyticsQueryRepository}, and returns a COMPACT JSON string of the top
 * rows. Nothing here writes; a name that resolves to nothing becomes a
 * {@code warnings} entry rather than a failed request.
 */
@Component
@RequiredArgsConstructor
public class SparkyTools {

    private final AnalyticsQueryRepository queryRepo;
    private final ProductRepository productRepository;
    private final OperationRepository operationRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ShiftRepository shiftRepository;
    // Self-contained: an initialised final field is excluded from the
    // @RequiredArgsConstructor, so no ObjectMapper bean needs to exist.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Upper bound on rows any single tool returns to the model, to keep the context compact. */
    private static final int MAX_ROWS = 50;

    // ── Tool schema definitions (sent as the `tools` array) ─────────────────

    public List<Object> definitions() {
        List<Object> tools = new ArrayList<>();
        tools.add(fn("product_operation_analytics",
                "Analitika po proizvodu i operaciji (količina, škart, kom/h, procenat škarta, učinak %). "
                        + "Koristi za pitanja o proizvodima/operacijama, najboljim ili najgorim operacijama, škartu i učinku.",
                obj(
                        prop("products", strArray("Nazivi ili šifre proizvoda")),
                        prop("operations", strArray("Nazivi operacija")),
                        prop("employees", strArray("Imena radnika")),
                        prop("dateFrom", str("Početni datum, YYYY-MM-DD")),
                        prop("dateTo", str("Krajnji datum, YYYY-MM-DD")),
                        prop("shifts", strArray("Nazivi ili šifre smena")),
                        prop("orders", strArray("Šifre ili nazivi naloga")),
                        prop("noteLike", str("Deo teksta napomene")),
                        prop("minPerformancePct", num("Minimalni učinak u procentima")),
                        prop("sortBy", enumStr("Sortiranje", "performance", "quantity", "perHour", "defect")),
                        prop("sortDir", enumStr("Smer sortiranja", "ASC", "DESC")),
                        prop("limit", num("Maksimalan broj redova (podrazumevano 10)"))
                ),
                List.of()));
        tools.add(fn("employee_analytics",
                "Analitika učinka radnika po proizvodu i operaciji (količina, učinak %, kom/h). "
                        + "Koristi za pitanja o radnicima i njihovom učinku.",
                obj(
                        prop("employees", strArray("Imena radnika")),
                        prop("products", strArray("Nazivi ili šifre proizvoda")),
                        prop("operations", strArray("Nazivi operacija")),
                        prop("dateFrom", str("Početni datum, YYYY-MM-DD")),
                        prop("dateTo", str("Krajnji datum, YYYY-MM-DD")),
                        prop("shifts", strArray("Nazivi ili šifre smena")),
                        prop("limit", num("Maksimalan broj redova (podrazumevano 10)"))
                ),
                List.of()));
        tools.add(fn("operations_with_note",
                "Pojedinačni radni nalozi (work logovi) čija napomena sadrži dati tekst — datum, smena, radnik, "
                        + "proizvod, operacija, trajanje. Koristi za pitanja tipa 'gde je zabeležena napomena X'.",
                obj(
                        prop("noteLike", str("Deo teksta napomene (obavezno)")),
                        prop("products", strArray("Nazivi ili šifre proizvoda")),
                        prop("operations", strArray("Nazivi operacija")),
                        prop("dateFrom", str("Početni datum, YYYY-MM-DD")),
                        prop("dateTo", str("Krajnji datum, YYYY-MM-DD")),
                        prop("limit", num("Maksimalan broj redova (podrazumevano 50)"))
                ),
                List.of("noteLike")));
        tools.add(fn("norm_basis",
                "Norma operacije naspram onoga što evidentiran rad pokazuje (trenutna norma, kom/h iz podataka). "
                        + "Koristi za pitanja o normama.",
                obj(
                        prop("products", strArray("Nazivi ili šifre proizvoda (obavezno)")),
                        prop("operations", strArray("Nazivi operacija")),
                        prop("dateFrom", str("Početni datum, YYYY-MM-DD")),
                        prop("dateTo", str("Krajnji datum, YYYY-MM-DD"))
                ),
                List.of("products")));
        tools.add(fn("resolve_options",
                "Pronađi šifarnik/id-eve za nejasan naziv (proizvod, operacija, radnik, nalog, smena). "
                        + "Koristi kada nisi siguran na šta se naziv odnosi.",
                obj(
                        prop("type", enumStr("Vrsta entiteta", "product", "operation", "employee", "order", "shift")),
                        prop("text", str("Tekst za pretragu"))
                ),
                List.of("type", "text")));
        return tools;
    }

    // ── Tool dispatch ───────────────────────────────────────────────────────

    /** Execute one tool call and return its result as a JSON string. */
    public String execute(String name, String argumentsJson) {
        try {
            Map<String, Object> args = parseArgs(argumentsJson);
            Object result = switch (name == null ? "" : name) {
                case "product_operation_analytics" -> productOperationAnalytics(args);
                case "employee_analytics" -> employeeAnalytics(args);
                case "operations_with_note" -> operationsWithNote(args);
                case "norm_basis" -> normBasis(args);
                case "resolve_options" -> resolveOptions(args);
                default -> Map.of("error", "Nepoznat alat: " + name);
            };
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            // Never leak stack traces to the model; give it a readable failure it can phrase.
            return "{\"error\":\"Greška pri izvršavanju alata.\"}";
        }
    }

    // ── Tool implementations ─────────────────────────────────────────────────

    private Map<String, Object> productOperationAnalytics(Map<String, Object> a) {
        List<String> warnings = new ArrayList<>();
        AnalyticsFilterRequest f = new AnalyticsFilterRequest();
        List<Long> productIds = resolveInto(strList(a, "products"), Entity.PRODUCT, warnings);
        f.setProductIds(productIds);
        f.setOperationIds(resolveOperationsScoped(strList(a, "operations"), productIds, warnings));
        f.setEmployeeIds(resolveInto(strList(a, "employees"), Entity.EMPLOYEE, warnings));
        f.setShiftIds(resolveInto(strList(a, "shifts"), Entity.SHIFT, warnings));
        f.setProductionOrderIds(resolveInto(strList(a, "orders"), Entity.ORDER, warnings));
        f.setDateFrom(date(a, "dateFrom", warnings));
        f.setDateTo(date(a, "dateTo", warnings));
        f.setNoteLike(str(a, "noteLike"));
        f.setMinPerformancePct(dec(a, "minPerformancePct"));

        int limit = limit(a, 10);
        f.setLevel("OPERATION");
        f.setPage(0);
        f.setSize(limit);
        f.setSortBy(mapSortBy(str(a, "sortBy")));
        if (f.getSortBy() != null) {
            f.setSortDir(sortDir(str(a, "sortDir"), "DESC"));
        }

        List<ProductOperationSummaryDto> rows = queryRepo.findProductOperationSummaryPage(f).content();
        List<Object> out = new ArrayList<>();
        for (ProductOperationSummaryDto r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product", r.getProductName());
            row.put("operation", r.getOperationName());
            row.put("quantity", r.getSumQuantity());
            row.put("scrap", r.getSumScrap());
            row.put("avgPerHour", round2(r.getAvgPerHour()));
            row.put("defectPct", round2(r.getDefectPct()));
            row.put("performancePct", round2(r.getAvgPerformancePct()));
            out.add(row);
        }
        return withWarnings(out, warnings);
    }

    private Map<String, Object> employeeAnalytics(Map<String, Object> a) {
        List<String> warnings = new ArrayList<>();
        AnalyticsFilterRequest f = new AnalyticsFilterRequest();
        f.setEmployeeIds(resolveInto(strList(a, "employees"), Entity.EMPLOYEE, warnings));
        List<Long> productIds = resolveInto(strList(a, "products"), Entity.PRODUCT, warnings);
        f.setProductIds(productIds);
        f.setOperationIds(resolveOperationsScoped(strList(a, "operations"), productIds, warnings));
        f.setShiftIds(resolveInto(strList(a, "shifts"), Entity.SHIFT, warnings));
        f.setDateFrom(date(a, "dateFrom", warnings));
        f.setDateTo(date(a, "dateTo", warnings));

        int limit = limit(a, 10);
        f.setPage(0);
        f.setSize(limit);

        List<EmployeeProductOperationDto> rows = queryRepo.findEmployeeEfficiencyPage(f).content();
        List<Object> out = new ArrayList<>();
        for (EmployeeProductOperationDto r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employee", r.getEmployeeName());
            row.put("product", r.getProductName());
            row.put("operation", r.getOperationName());
            row.put("quantity", r.getSumQuantity());
            row.put("performancePct", round2(r.getAvgPerformancePct()));
            row.put("perHour", perHour(r.getSumQuantity(), r.getSumDurationMin()));
            out.add(row);
        }
        return withWarnings(out, warnings);
    }

    private Map<String, Object> operationsWithNote(Map<String, Object> a) {
        List<String> warnings = new ArrayList<>();
        String noteLike = str(a, "noteLike");
        if (noteLike == null || noteLike.isBlank()) {
            return Map.of("warning", "Nedostaje tekst napomene.");
        }
        AnalyticsFilterRequest f = new AnalyticsFilterRequest();
        f.setNoteLike(noteLike);
        List<Long> productIds = resolveInto(strList(a, "products"), Entity.PRODUCT, warnings);
        f.setProductIds(productIds);
        f.setOperationIds(resolveOperationsScoped(strList(a, "operations"), productIds, warnings));
        f.setDateFrom(date(a, "dateFrom", warnings));
        f.setDateTo(date(a, "dateTo", warnings));

        int limit = limit(a, 50);
        List<NoteOccurrenceDto> rows = queryRepo.findNoteOccurrences(f, limit);
        List<Object> out = new ArrayList<>();
        for (NoteOccurrenceDto r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", r.getWorkDate());
            row.put("shift", r.getShiftCode());
            row.put("employee", r.getEmployeeName());
            row.put("product", r.getProductName());
            row.put("operation", r.getOperationName());
            row.put("durationMin", r.getDurationMin());
            out.add(row);
        }
        return withWarnings(out, warnings);
    }

    private Map<String, Object> normBasis(Map<String, Object> a) {
        List<String> warnings = new ArrayList<>();
        List<Long> productIds = resolveInto(strList(a, "products"), Entity.PRODUCT, warnings);
        if (productIds == null || productIds.isEmpty()) {
            return Map.of("warning", "Nije pronađen nijedan proizvod za normu.");
        }
        AnalyticsFilterRequest f = new AnalyticsFilterRequest();
        f.setProductIds(productIds);
        f.setOperationIds(resolveOperationsScoped(strList(a, "operations"), productIds, warnings));
        f.setDateFrom(date(a, "dateFrom", warnings));
        f.setDateTo(date(a, "dateTo", warnings));

        List<NormBasisDto> rows = queryRepo.findNormBasis(f);
        List<Object> out = new ArrayList<>();
        for (NormBasisDto r : rows.stream().limit(MAX_ROWS).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", r.getOperationName());
            row.put("currentNorm", r.getCurrentNorm());
            row.put("normDate", r.getNormDate());
            row.put("unitsPerProduct", r.getUnitsPerProduct());
            row.put("sumQuantity", r.getSumQuantity());
            row.put("sumDurationMin", r.getSumDurationMin());
            row.put("avgPerHour", round2(r.getAvgPerHour()));
            out.add(row);
        }
        return withWarnings(out, warnings);
    }

    private Map<String, Object> resolveOptions(Map<String, Object> a) {
        String type = str(a, "type");
        String text = str(a, "text");
        if (type == null || text == null || text.isBlank()) {
            return Map.of("warning", "Nedostaje tip ili tekst za pretragu.");
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        switch (type.toLowerCase()) {
            case "product" -> SparkyNameMatcher.rank(candidatesForProduct(text), text).stream().limit(8)
                    .forEach(c -> matches.add(option(c.id(), label(c.name(), c.code()))));
            case "operation" -> {
                // Rank on op name; the product name rides along only as the label subtitle.
                Map<Long, String> subtitle = new LinkedHashMap<>();
                List<SparkyNameMatcher.Candidate> cands = new ArrayList<>();
                for (var o : operationRepository.searchFolded(SparkyNameMatcher.fold(text), null)) {
                    cands.add(new SparkyNameMatcher.Candidate(o.getId(), o.getOpName(), null, o.getProductId()));
                    subtitle.put(o.getId(), o.getProductName());
                }
                SparkyNameMatcher.rank(cands, text).stream().limit(8)
                        .forEach(c -> matches.add(option(c.id(), label(c.name(), subtitle.get(c.id())))));
            }
            case "employee" -> SparkyNameMatcher.rank(candidatesForEmployee(text), text).stream().limit(8)
                    .forEach(c -> matches.add(option(c.id(), label(c.name(), c.code()))));
            case "order" -> SparkyNameMatcher.rank(candidatesForOrder(text), text).stream().limit(8)
                    .forEach(c -> matches.add(option(c.id(), label(c.name(), c.code()))));
            case "shift" -> matchShifts(text).stream().limit(8)
                    .forEach(s -> matches.add(option(s.getId(), label(s.getName(), s.getShiftCode()))));
            default -> {
                return Map.of("warning", "Nepoznat tip: " + type);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("matches", matches);
        if (matches.isEmpty()) {
            res.put("warning", "Nije pronađeno: " + text);
        }
        return res;
    }

    // ── Name resolution ───────────────────────────────────────────────────────

    private enum Entity {PRODUCT, OPERATION, EMPLOYEE, ORDER, SHIFT}

    /**
     * Resolve each free-text name to the id of its BEST diacritic-folded,
     * partial-token match; unresolved names become "Nije pronađeno: X" warnings.
     * Returns null when nothing was asked for (so the filter field stays null and
     * its filter is not applied). For operations prefer
     * {@link #resolveOperationsScoped} so they can be scoped to a product.
     */
    private List<Long> resolveInto(List<String> names, Entity entity, List<String> warnings) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (String name : names) {
            Long id = switch (entity) {
                case PRODUCT -> bestId(candidatesForProduct(name), name);
                case OPERATION -> bestId(candidatesForOperation(name, null), name);
                case EMPLOYEE -> bestId(candidatesForEmployee(name), name);
                case ORDER -> bestId(candidatesForOrder(name), name);
                case SHIFT -> matchShifts(name).stream().findFirst().map(Shift::getId).orElse(null);
            };
            if (id != null) {
                ids.add(id);
            } else {
                warnings.add("Nije pronađeno: " + name);
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * Resolve operation names, preferring matches AMONG the resolved products'
     * operations (so "operacija 3" for "Kućište pumpe" is that product's Operacija
     * 3), and falling back to a global match only when no product-scoped match
     * exists. Unresolved names become warnings.
     */
    private List<Long> resolveOperationsScoped(List<String> names, List<Long> productIds, List<String> warnings) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (String name : names) {
            Long id = null;
            if (productIds != null && !productIds.isEmpty()) {
                List<SparkyNameMatcher.Candidate> scoped = new ArrayList<>();
                for (Long pid : productIds) {
                    scoped.addAll(candidatesForOperation(name, pid));
                }
                id = bestId(scoped, name);
            }
            if (id == null) {
                id = bestId(candidatesForOperation(name, null), name);
            }
            if (id != null) {
                ids.add(id);
            } else {
                warnings.add("Nije pronađeno: " + name);
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    // Candidate fetchers — fold the query in Java, let the DB fold the column.

    private List<SparkyNameMatcher.Candidate> candidatesForProduct(String name) {
        String folded = SparkyNameMatcher.fold(name);
        if (folded.isBlank()) {
            return List.of();
        }
        return productRepository.searchFolded(folded).stream()
                .map(p -> new SparkyNameMatcher.Candidate(p.getId(), p.getProductName(), p.getProductCode(), null))
                .toList();
    }

    private List<SparkyNameMatcher.Candidate> candidatesForOperation(String name, Long productId) {
        String folded = SparkyNameMatcher.fold(name);
        if (folded.isBlank()) {
            return List.of();
        }
        return operationRepository.searchFolded(folded, productId).stream()
                .map(o -> new SparkyNameMatcher.Candidate(o.getId(), o.getOpName(), null, o.getProductId()))
                .toList();
    }

    private List<SparkyNameMatcher.Candidate> candidatesForEmployee(String name) {
        String folded = SparkyNameMatcher.fold(name);
        if (folded.isBlank()) {
            return List.of();
        }
        return employeeRepository.searchFolded(folded).stream()
                .map(e -> new SparkyNameMatcher.Candidate(e.getId(), e.getFullName(), e.getEmployeeNo(), null))
                .toList();
    }

    private List<SparkyNameMatcher.Candidate> candidatesForOrder(String name) {
        String folded = SparkyNameMatcher.fold(name);
        if (folded.isBlank()) {
            return List.of();
        }
        return productionOrderRepository.searchFolded(folded).stream()
                .map(o -> new SparkyNameMatcher.Candidate(o.getId(), o.getName(), o.getCode(), null))
                .toList();
    }

    private Long bestId(List<SparkyNameMatcher.Candidate> candidates, String query) {
        SparkyNameMatcher.Candidate best = SparkyNameMatcher.best(candidates, query);
        return best == null ? null : best.id();
    }

    /** Shift matches by exact folded code/name first, then a folded contains. */
    private List<Shift> matchShifts(String text) {
        String needle = SparkyNameMatcher.fold(text);
        if (needle.isBlank()) {
            return List.of();
        }
        List<Shift> active = shiftRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc();
        List<Shift> exact = active.stream()
                .filter(s -> foldEq(s.getShiftCode(), needle) || foldEq(s.getName(), needle))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return active.stream()
                .filter(s -> foldContains(s.getShiftCode(), needle) || foldContains(s.getName(), needle))
                .toList();
    }

    private boolean foldEq(String value, String foldedNeedle) {
        return value != null && SparkyNameMatcher.fold(value).equals(foldedNeedle);
    }

    private boolean foldContains(String value, String foldedNeedle) {
        return value != null && SparkyNameMatcher.fold(value).contains(foldedNeedle);
    }

    // ── Argument parsing helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, Map.class);
    }

    private List<String> strList(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            List<String> out = list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            return out.isEmpty() ? null : out;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : List.of(s);
    }

    private String str(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private LocalDate date(Map<String, Object> a, String key, List<String> warnings) {
        String s = str(a, key);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            warnings.add("Neispravan datum: " + s);
            return null;
        }
    }

    private BigDecimal dec(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int limit(Map<String, Object> a, int defaultValue) {
        Object v = a.get("limit");
        if (v == null) {
            return defaultValue;
        }
        try {
            int n = (int) Math.round(Double.parseDouble(v.toString().trim()));
            return Math.max(1, Math.min(n, MAX_ROWS));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** Sparky's sort keys → the repository's own whitelist keys. */
    private String mapSortBy(String sortBy) {
        if (sortBy == null) {
            return null;
        }
        return switch (sortBy.toLowerCase()) {
            case "performance" -> "avgPerformancePct";
            case "quantity" -> "sumQuantity";
            case "perhour" -> "avgPerHour";
            case "defect" -> "defectPct";
            default -> null;
        };
    }

    private String sortDir(String dir, String fallback) {
        if (dir == null) {
            return fallback;
        }
        return "ASC".equalsIgnoreCase(dir) ? "ASC" : "DESC";
    }

    // ── Output helpers ────────────────────────────────────────────────────────

    private Map<String, Object> withWarnings(List<Object> rows, List<String> warnings) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("rows", rows);
        if (!warnings.isEmpty()) {
            res.put("warnings", warnings);
        }
        return res;
    }

    private Map<String, Object> option(Long id, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }

    private String label(String primary, String secondary) {
        if (secondary == null || secondary.isBlank()) {
            return primary;
        }
        return primary + " (" + secondary + ")";
    }

    private BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal perHour(Long quantity, Long durationMin) {
        if (quantity == null || durationMin == null || durationMin <= 0) {
            return null;
        }
        return BigDecimal.valueOf(quantity * 60.0 / durationMin).setScale(2, RoundingMode.HALF_UP);
    }

    // ── JSON-schema builders ──────────────────────────────────────────────────

    private Object fn(String name, String description, Map<String, Object> parameters, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>(parameters);
        params.put("required", required);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", params);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    @SafeVarargs
    private Map<String, Object> obj(Map.Entry<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : props) {
            properties.put(p.getKey(), p.getValue());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private Map.Entry<String, Object> prop(String name, Object schema) {
        return Map.entry(name, schema);
    }

    private Map<String, Object> str(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    private Map<String, Object> num(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "number");
        m.put("description", description);
        return m;
    }

    private Map<String, Object> enumStr(String description, String... values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("enum", List.of(values));
        return m;
    }

    private Map<String, Object> strArray(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("description", description);
        m.put("items", Map.of("type", "string"));
        return m;
    }
}
