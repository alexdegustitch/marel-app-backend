package com.aleksandarparipovic.marel_app.common;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftOverlapException;
import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<?> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", ex.getMessage()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", ex.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationErrors(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .toList();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "errors", errors
                ));
    }

    /**
     * Correct password, unusable account. 403 rather than 401 so the client knows
     * the credentials were fine, plus an explicit accountStatus so it can render
     * the pending-approval screen instead of a login error.
     */
    @ExceptionHandler(com.aleksandarparipovic.marel_app.auth.AccountNotUsableException.class)
    public ResponseEntity<?> handleAccountNotUsable(
            com.aleksandarparipovic.marel_app.auth.AccountNotUsableException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", ex.getMessage(),
                        "code", "ACCOUNT_NOT_USABLE",
                        "accountStatus", ex.getAccountStatus().name()
                ));
    }

    /**
     * A malformed or unparseable request body — an invalid enum value, a JSON type
     * that does not match the target field, or plain broken JSON. This is the
     * client's mistake, so 400; without this handler it fell through to the generic
     * 500 and reported "Something went wrong" for a simple typo.
     */
    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.http.converter.HttpMessageConversionException.class
    })
    public ResponseEntity<?> handleUnreadableBody(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        // The exception message can quote the offending payload, so it
                        // is deliberately not echoed back.
                        "error", "Neispravan format zahteva."
                ));
    }

    /**
     * A shift collides with one the employee already has.
     *
     * <p>409 like any other conflict, but with the collision and the ways out in
     * the body: the client is meant to ASK the user, not report a failure. Before
     * this, ex_work_shifts_no_overlap reached the screen as a raw SQL error naming
     * a tstzrange.
     */
    @ExceptionHandler(WorkShiftOverlapException.class)
    public ResponseEntity<?> handleWorkShiftOverlap(WorkShiftOverlapException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", ex.getMessage());
        body.put("code", "WORK_SHIFT_OVERLAP");
        body.put("details", Map.of(
                "conflicts", ex.getConflicts(),
                "options", ex.getOptions()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> handleConflict(ConflictException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", ex.getMessage()
                ));
    }

    /**
     * Two users changed the same row concurrently and this one lost. The message
     * is generic on purpose — the loser only needs to know to reload and retry,
     * and the winner's identity is not theirs to learn from an error body.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", "Neko drugi je u međuvremenu izmenio ovaj zapis. Osvežite i pokušajte ponovo."
                ));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex
    ) {
        // Rethrown rather than swallowed into the generic 500 handler below, which
        // would otherwise turn every authorization failure into "Something went wrong".
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", "Nemate ovlašćenje za ovu akciju."
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", "Something went wrong. Please try again."
                ));
    }

    @ExceptionHandler(WrongPasswordException.class)
    public ResponseEntity<?> handleWrongPassword(WrongPasswordException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "error", ex.getMessage(),
                        "code", "WRONG_PASSWORD"
                ));
    }
}
