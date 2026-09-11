package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.order_email_view.OrderEmailView;

/**
 * Renders an {@link OrderEmailView} as the table the recipients read.
 *
 * <p>Two modes, one markup. {@link Mode#DIFF} is the mail body after an edit:
 * what was removed or overwritten arrives struck through, what was added
 * arrives bold, and the rest stays plain — so a reader sees the whole order
 * AND what this save did to it in one look. {@link Mode#CLEAN} is the order
 * document itself (the created mail, the PDF attachment): current state only,
 * removed rows omitted, nothing marked.
 *
 * <p>Inline styles throughout, same as {@link NotificationEmailComposer}:
 * business inboxes strip stylesheets, and the PDF renderer reads the same
 * attributes. Every value is escaped — order names, notes and product names
 * are typed by users and must never arrive as markup.
 */
public final class OrderEmailHtmlRenderer {

    public enum Mode { DIFF, CLEAN }

    private static final String CELL_STYLE =
            "border:1px solid #dee2e6;padding:6px 8px;vertical-align:top;text-align:left";
    private static final String HEAD_STYLE = CELL_STYLE + ";background:#f1f3f5";
    private static final String STRUCK = "color:#868e96;text-decoration:line-through";

    private OrderEmailHtmlRenderer() {
    }

    public static String render(OrderEmailView view, Mode mode) {
        StringBuilder html = new StringBuilder();

        renderFields(html, view, mode);
        renderItems(html, view, mode);
        renderDeadlines(html, view, mode);

        return html.toString();
    }

    private static void renderFields(StringBuilder html, OrderEmailView view, Mode mode) {
        StringBuilder rows = new StringBuilder();
        for (OrderEmailView.Field field : view.fields()) {
            String cell = renderCell(field.cell(), mode);
            // An unset field that this save did not touch is not information —
            // a mail listing six "nije postavljeno" rows buries the one row
            // that matters.
            if (cell.isEmpty()) {
                continue;
            }
            rows.append("<tr><td style=\"padding:2px 12px 2px 0;color:#868e96;")
                    .append("white-space:nowrap;vertical-align:top\">")
                    .append(escape(field.label()))
                    .append("</td><td style=\"padding:2px 0;vertical-align:top\">")
                    .append(cell)
                    .append("</td></tr>");
        }
        if (!rows.isEmpty()) {
            html.append("<table style=\"border-collapse:collapse;margin:0 0 12px;")
                    .append("font-size:14px\">")
                    .append(rows)
                    .append("</table>");
        }
    }

    private static void renderItems(StringBuilder html, OrderEmailView view, Mode mode) {
        if (view.items().isEmpty()) {
            return;
        }

        html.append("<table style=\"border-collapse:collapse;width:100%;margin:0 0 12px;")
                .append("font-size:14px\">")
                .append("<tr>")
                .append("<th style=\"").append(HEAD_STYLE).append("\">#</th>")
                .append("<th style=\"").append(HEAD_STYLE).append("\">Proizvod</th>")
                .append("<th style=\"").append(HEAD_STYLE).append("\">Količina (kom)</th>")
                .append("<th style=\"").append(HEAD_STYLE).append("\">Napomena</th>")
                .append("</tr>");

        int position = 0;
        for (OrderEmailView.Item item : view.items()) {
            boolean removed = OrderEmailView.REMOVED.equals(item.status());
            if (removed && mode == Mode.CLEAN) {
                continue;
            }
            // A removed line keeps no number: the remaining lines are the
            // order, and their numbering must read as if it were never there.
            String number = removed ? "—" : String.valueOf(++position);

            html.append("<tr>")
                    .append(itemCell(number, item.status(), mode))
                    .append(itemCell(renderCell(item.name(), mode), item.status(), mode))
                    .append(itemCell(renderCell(item.quantity(), mode), item.status(), mode))
                    .append(itemCell(renderCell(item.note(), mode), item.status(), mode))
                    .append("</tr>");
        }

        html.append("</table>");
    }

    /**
     * A removed row is struck through WHOLE and an added one is bold whole —
     * the row's fate is the message, not any single cell of it.
     */
    private static String itemCell(String inner, String status, Mode mode) {
        String content = inner;
        if (mode == Mode.DIFF && OrderEmailView.REMOVED.equals(status)) {
            content = "<del style=\"" + STRUCK + "\">" + inner + "</del>";
        } else if (mode == Mode.DIFF && OrderEmailView.ADDED.equals(status)) {
            content = "<b>" + inner + "</b>";
        }
        return "<td style=\"" + CELL_STYLE + "\">" + content + "</td>";
    }

    private static void renderDeadlines(StringBuilder html, OrderEmailView view, Mode mode) {
        StringBuilder lines = new StringBuilder();
        for (OrderEmailView.Line line : view.deadlines()) {
            boolean removed = OrderEmailView.REMOVED.equals(line.status());
            if (removed && mode == Mode.CLEAN) {
                continue;
            }
            String text = escape(line.text());
            if (mode == Mode.DIFF && removed) {
                text = "<del style=\"" + STRUCK + "\">" + text + "</del>";
            } else if (mode == Mode.DIFF && OrderEmailView.ADDED.equals(line.status())) {
                text = "<b>" + text + "</b>";
            }
            lines.append("<li style=\"margin:0 0 2px\">").append(text).append("</li>");
        }
        if (!lines.isEmpty()) {
            html.append("<p style=\"margin:0 0 4px;color:#868e96\">Rokovi isporuke:</p>")
                    .append("<ul style=\"margin:0 0 12px;padding-left:20px;font-size:14px\">")
                    .append(lines)
                    .append("</ul>");
        }
    }

    /**
     * One cell's content. In DIFF mode the previous value arrives struck
     * through before the bold new one; a value this save cleared arrives as
     * only the struck-through past. Empty string means "nothing to show" —
     * the caller decides whether that hides the row.
     */
    private static String renderCell(OrderEmailView.Cell cell, Mode mode) {
        if (cell == null) {
            return "";
        }
        String value = cell.value() == null ? "" : escape(cell.value());

        if (mode == Mode.CLEAN || !cell.changed()) {
            return value;
        }

        StringBuilder out = new StringBuilder();
        if (cell.was() != null && !cell.was().isBlank()) {
            out.append("<del style=\"").append(STRUCK).append("\">")
                    .append(escape(cell.was()))
                    .append("</del>");
        }
        if (!value.isEmpty()) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append("<b>").append(value).append("</b>");
        }
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
