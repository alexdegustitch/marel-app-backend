package com.aleksandarparipovic.marel_app.order_note;

import java.util.List;
import java.util.Map;

/**
 * Flattens a rich-text note document to plain text.
 *
 * <p>The document arrives as plain deserialised JSON — nested {@link List}s and
 * {@link Map}s, the shape the editor's {@code getJSON()} produces — rather than
 * as any particular library's tree, so this stays clear of the Jackson version
 * the HTTP layer happens to use. A node carries its child nodes under
 * {@code content} and a text leaf carries its {@code text}; that is all the
 * structure this needs to reach every word.
 *
 * <p>The result is what the database searches, sorts and previews on. It is
 * derived here, in one place, so {@code body_text} can never say something the
 * stored document does not — and it is deliberately forgiving: a node shaped in
 * a way this does not recognise contributes nothing rather than throwing. Losing
 * a word from the SEARCH INDEX is recoverable; refusing to save a note somebody
 * wrote is not.
 */
public final class NoteText {

    private NoteText() {
    }

    public static String flatten(Object document) {
        StringBuilder out = new StringBuilder();
        walk(document, out);
        // Collapse the runs of whitespace the walk leaves between nodes.
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    private static void walk(Object node, StringBuilder out) {
        if (node == null) {
            return;
        }

        if (node instanceof String s) {
            out.append(s).append(' ');
            return;
        }

        if (node instanceof List<?> list) {
            for (Object child : list) {
                walk(child, out);
            }
            return;
        }

        if (node instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text instanceof String s) {
                out.append(s).append(' ');
            }
            // Child nodes hang off `content`; nothing else needs walking.
            walk(map.get("content"), out);
        }
    }
}
