package com.aleksandarparipovic.marel_app.order_note.dto;

import java.time.OffsetDateTime;

/**
 * One note as a reader sees it. {@code body} is the document to render, handed
 * back as plain deserialised JSON (lists and maps) so the HTTP layer serialises
 * it whatever Jackson version it runs; {@code bodyText} is the plain-text copy
 * (used for the collapsed preview); {@code canEdit} / {@code canDelete} are
 * computed FOR THIS READER, so the screen offers exactly the controls the server
 * will honour — its own notes, plus anything if the reader moderates the wall.
 */
public record OrderNoteDto(
        Long id,
        Long authorUserId,
        String authorName,
        Object body,
        String bodyText,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean edited,
        boolean canEdit,
        boolean canDelete
) {
}
