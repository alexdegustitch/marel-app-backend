package com.aleksandarparipovic.marel_app.operation.dto;

import java.util.List;

/**
 * What a copy actually did, by operation name — the modal reports both halves
 * ("dodato 3, preskočeno 2") instead of a silent success that hid the skips.
 */
public record CopyOperationsResult(
        List<String> copied,
        List<String> skipped
) {
}
