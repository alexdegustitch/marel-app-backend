package com.aleksandarparipovic.marel_app.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counting a wrong code, in a transaction of its own.
 *
 * <p><b>This bean exists because of a rollback.</b> {@code confirm} refuses a
 * wrong code by throwing, and an unchecked exception out of a {@code @Transactional}
 * method rolls that transaction back — taking the incremented counter with it.
 * The count therefore never moved: the screen went on saying five attempts left
 * however many were spent, and the limit that makes a six-digit code safe to send
 * by mail never engaged at all. Six digits is a million possibilities, which is
 * nothing to a machine allowed to keep guessing for the half hour the code lives.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one
 * on its own, so the attempt survives the refusal that follows it. It has to be a
 * SEPARATE BEAN: a method calling its own class's {@code @Transactional} method
 * goes straight down the inside of the object and never through the proxy that
 * would start the new transaction — the annotation would be decoration.
 *
 * <p>The caller must not touch the request entity afterwards. Its own copy is
 * stale the moment this returns, and writing it back would undo the count a
 * second way.
 */
@Component
@RequiredArgsConstructor
class EmailChangeAttempts {

    private final EmailChangeRequestRepository requestRepository;

    /**
     * Record one wrong guess against a request and answer how many are left.
     *
     * <p>Re-read inside this transaction rather than taken as an argument: the
     * caller's copy was loaded before the suspension and could be a guess behind
     * a concurrent attempt on the same request.
     *
     * @return attempts remaining, never negative
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long requestId) {
        EmailChangeRequest request = requestRepository.findById(requestId).orElse(null);
        if (request == null) {
            // Cancelled or confirmed from another session in the moment between
            // the check and here. Nothing to count and nothing to report.
            return 0;
        }

        request.setAttempts(request.getAttempts() + 1);
        requestRepository.saveAndFlush(request);

        return Math.max(0, EmailChangeService.MAX_ATTEMPTS - request.getAttempts());
    }
}
