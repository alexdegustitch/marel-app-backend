package com.aleksandarparipovic.marel_app.assistant;

import lombok.Getter;

/**
 * Thrown when a caller has used up their daily allowance of assistant questions.
 * Carries the limit so the controller can name it in the 429 message.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final int limit;

    public QuotaExceededException(int limit) {
        super("Daily assistant quota exceeded: " + limit);
        this.limit = limit;
    }
}
