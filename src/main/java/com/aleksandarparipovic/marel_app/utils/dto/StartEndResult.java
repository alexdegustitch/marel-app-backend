package com.aleksandarparipovic.marel_app.utils.dto;

import java.time.OffsetDateTime;

public record StartEndResult(
        OffsetDateTime start,
        OffsetDateTime end
) {}