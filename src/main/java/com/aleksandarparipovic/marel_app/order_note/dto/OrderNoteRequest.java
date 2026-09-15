package com.aleksandarparipovic.marel_app.order_note.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The body of a note being written or edited — the BlockNote document, nothing
 * else. Typed as {@code Object} so the HTTP layer deserialises the JSON into
 * plain lists and maps, clear of any one Jackson version's node type; the
 * service turns it into the jsonb the database holds. The author is the caller
 * (never trusted from the body), and whether the document actually says anything
 * is checked in the service, which flattens it and refuses an empty note.
 */
public record OrderNoteRequest(
        @NotNull Object body
) {
}
