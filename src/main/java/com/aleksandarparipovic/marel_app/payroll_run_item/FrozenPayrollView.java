package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_field_access.PayrollFieldAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A handed-over payroll, replayed for somebody who may not see all of it.
 *
 * <p>Reads the STORED document and never the live one. Once a supervisor has
 * handed a payroll over it stops moving for them: whatever payroll changes
 * afterwards, they open the same figures they submitted. Nothing here consults
 * the database for an amount — the record is the source.
 *
 * <p>What it does change is how much of that record the reader receives. The
 * lines they may not see are dropped before the response leaves the server, and
 * the totals are re-evaluated over what remains, by the same
 * {@link PayrollTotals} expression the engine uses. So the frozen screen adds up
 * for the same reason the live one does.
 *
 * <p>It works on the raw map rather than the response objects on purpose. The
 * detail DTOs are built from entities and cannot be read back into; and reading
 * them back would mean reconstructing a document that must be served exactly as
 * it was written.
 */
@Component
@RequiredArgsConstructor
public class FrozenPayrollView {

    private static final PayrollFieldAccessService.Access DENIED =
            new PayrollFieldAccessService.Access(false, false);

    private final PayrollFieldAccessService fieldAccessService;

    /**
     * @param detail       the stored {@code PayrollRunItemDetailResponse}, as JSON
     * @param visibleStatus the status this reader is allowed to be told — a lock
     *                      applied after the handover is not theirs to learn about
     * @param permissions  freshly resolved for the CURRENT reader. The snapshot's
     *                     own copy belongs to whoever submitted it, and serving it
     *                     back would offer this reader somebody else's buttons.
     */
    public Map<String, Object> filtered(Map<String, Object> detail,
                                        String visibleStatus,
                                        Object permissions) {

        Map<String, PayrollFieldAccessService.Access> access = fieldAccessService.accessForCurrentUser();

        Map<String, Object> out = new LinkedHashMap<>(detail);

        // ── Lines ────────────────────────────────────────────────────────────
        List<Map<String, Object>> keptSections = new ArrayList<>();
        List<PayrollTotals.Line> visibleLines = new ArrayList<>();
        long shownBefore = 0;

        for (Map<String, Object> section : maps(detail.get("adjustments"))) {
            shownBefore += maps(section.get("adjustments")).size();
            List<Map<String, Object>> kept = maps(section.get("adjustments")).stream()
                    .filter(line -> access.getOrDefault(text(line.get("categoryCode")), DENIED).canView())
                    // The record was written with every line editable, because it
                    // was written as payroll sees it. Editability is the reader's
                    // question, so it is answered here rather than replayed.
                    .map(line -> readOnlyUnlessAllowed(line, access))
                    .toList();
            if (kept.isEmpty()) {
                // An empty section is a heading with nothing under it, which
                // reads as "there was nothing here" rather than "not for you".
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(section);
            copy.put("adjustments", kept);
            keptSections.add(copy);

            kept.forEach(line -> visibleLines.add(new PayrollTotals.Line(
                    text(line.get("impactCode")),
                    text(line.get("sectionCode")),
                    Boolean.TRUE.equals(line.get("isApplied")),
                    decimal(line.get("amount")))));
        }
        out.put("adjustments", keptSections);

        // ── Totals over exactly those lines ──────────────────────────────────
        BigDecimal categoriesSum = maps(detail.get("categories")).stream()
                .map(c -> decimal(c.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> summary = new LinkedHashMap<>(map(detail.get("summary")));

        PayrollTotals totals = PayrollTotals.ofValues(
                categoriesSum, visibleLines, decimal(summary.get("previousNetPayableAmount")));

        summary.put("totalNetEarnings", totals.totalNetEarnings());
        summary.put("previouslyPaidAmount", totals.previouslyPaidAmount());
        summary.put("currentBalanceAmount", totals.currentBalanceAmount());
        summary.put("netPayableAmount", totals.netPayableAmount());

        // The headline figures are configurable in their own right: a role may be
        // allowed to read the lines and still not the payout.
        if (!access.getOrDefault(PayrollFieldAccessService.FIELD_NET_PAYABLE, DENIED).canView()) {
            summary.put("netPayableAmount", null);
        }
        if (!access.getOrDefault(PayrollFieldAccessService.FIELD_TOTAL_NET_EARNINGS, DENIED).canView()) {
            summary.put("totalNetEarnings", null);
        }
        if (!access.getOrDefault(PayrollFieldAccessService.FIELD_HOURLY_RATE, DENIED).canView()) {
            summary.put("hourlyRate", null);
        }

        // Said only when something was actually withheld from THIS reader.
        out.put("partialView", visibleLines.size() < shownBefore
                || summary.get("netPayableAmount") == null
                || summary.get("totalNetEarnings") == null);

        if (visibleStatus != null) {
            summary.put("status", visibleStatus);
        }
        out.put("summary", summary);

        if (permissions != null) {
            out.put("permissions", permissions);
        }
        return out;
    }

    private static Map<String, Object> readOnlyUnlessAllowed(
            Map<String, Object> line, Map<String, PayrollFieldAccessService.Access> access) {

        if (access.getOrDefault(text(line.get("categoryCode")), DENIED).canEdit()) {
            return line;
        }
        Map<String, Object> copy = new LinkedHashMap<>(line);
        copy.put("editableInput", "NONE");
        copy.put("allowTotalOverride", false);
        return copy;
    }

    // ── Reading JSON that has been through a database ────────────────────────
    //
    // jsonb comes back as plain maps, lists and boxed numbers; a BigDecimal that
    // went in may come back a Double. Rendered through Double.toString the
    // shortest round-tripping decimal is the one that was written, so parsing
    // that text restores the amount exactly — never the double's binary value.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(e -> (Map<String, Object>) e)
                .toList();
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
}
