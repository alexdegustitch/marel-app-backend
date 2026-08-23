package com.aleksandarparipovic.marel_app.operation_norm_version;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The one place that decides which norm an operation works to.
 *
 * <p>Three records state that fact and they must never disagree: the flag on the
 * version, the chronology of decisions, and the operation's own columns, which
 * are what payroll and the manufacturing-time report actually read. They can only
 * drift if something writes one without the others — so nothing else does. This
 * class exists because there are two callers with the fact pointing opposite ways:
 *
 * <ul>
 *   <li>the operation page decides on a VERSION and the columns follow it
 *       ({@link #putInForce});
 *   <li>the operation form writes the COLUMNS and the history has to follow them
 *       ({@link #recordCurrentFromOperation}).
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OperationNormInForceService {

    private final OperationNormVersionRepository versionRepository;
    private final OperationNormActivationRepository activationRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    /**
     * Puts a version in force and copies it onto the operation.
     *
     * <p>The version is the source of truth here: this is the operation page's
     * direction, where somebody chose a norm and the columns must catch up.
     */
    public void putInForce(
            Operation operation,
            OperationNormVersion version,
            User by,
            String reason,
            OperationNormActivation.Source source
    ) {
        claim(operation, version, by, reason, source);
        applyToOperation(operation, version);
    }

    /**
     * Brings the history in step with the norm the operation now carries.
     *
     * <p>The columns are the source of truth here — the operation form has just
     * written them — so this does NOT write them back. What it does:
     *
     * <ul>
     *   <li>values unchanged → nothing happens, so saving an operation without
     *       touching its norm does not litter the chronology;
     *   <li>values changed with a norm in force → that version takes the new
     *       values and the change is recorded as an edit, which is the same thing
     *       "Izmeni važeću normu" does on the operation page;
     *   <li>values changed with nothing in force → the norm is recorded as the
     *       first version, because a norm the history never saw is a norm nobody
     *       can account for;
     *   <li>the norm removed altogether → the version in force is archived and
     *       nothing inherits. The form said this operation has no norm; promoting
     *       an older one would be the code overruling that.
     * </ul>
     */
    public void recordCurrentFromOperation(Operation operation, User by) {
        OperationNormVersion current = versionRepository
                .findFirstByOperation_IdAndCurrentTrue(operation.getId())
                .orElse(null);

        if (!hasNorm(operation)) {
            if (current == null) return;
            current.setArchivedAt(OffsetDateTime.now());
            current.setCurrent(false);
            versionRepository.saveAndFlush(current);
            return;
        }

        if (current != null && sameValues(current, operation)) return;

        OperationNormVersion version = current != null ? current : OperationNormVersion.builder()
                .operation(operation)
                .createdBy(by)
                .build();

        version.setMinNorm(operation.getMinNorm());
        version.setMaxNorm(operation.getMaxNorm());
        version.setUnitsPerProduct(operation.getUnitsPerProduct());
        version.setNormDate(operation.getNormDate());
        // A dated norm is not a temporary one, and the database says so too.
        if (operation.getNormDate() != null) {
            version.setTemporary(false);
        }
        version = versionRepository.saveAndFlush(version);

        claim(operation, version, by, null, current != null
                ? OperationNormActivation.Source.EDITED
                : OperationNormActivation.Source.ADDED);
    }

    /** Whoever is signed in, as a {@link User}, or null outside a request. */
    public User currentUser() {
        Long id = currentUserService.getCurrentUserId();
        return id == null ? null : userRepository.findById(id).orElse(null);
    }

    /** The flag and the chronology — the half of the fact that never varies. */
    private void claim(
            Operation operation,
            OperationNormVersion version,
            User by,
            String reason,
            OperationNormActivation.Source source
    ) {
        versionRepository.findFirstByOperation_IdAndCurrentTrue(operation.getId())
                .filter(previous -> !previous.getId().equals(version.getId()))
                .ifPresent(previous -> {
                    previous.setCurrent(false);
                    // Flushed before the new flag is set: the partial unique index
                    // is not deferrable, so the two updates must not meet.
                    versionRepository.saveAndFlush(previous);
                });

        version.setCurrent(true);
        versionRepository.saveAndFlush(version);

        activationRepository.save(OperationNormActivation.builder()
                .operation(operation)
                .normVersion(version)
                .activatedBy(by)
                .reason(reason)
                .source(source)
                .build());
    }

    /**
     * Copies a version onto the operation's own columns — what payroll and the
     * manufacturing-time report read. A norm that did not land here would be a
     * norm nobody works to.
     */
    public static void applyToOperation(Operation operation, OperationNormVersion version) {
        operation.setMinNorm(version.getMinNorm());
        operation.setMaxNorm(version.getMaxNorm());
        // The database CHECK ties norm_required to the pair being present.
        operation.setNormRequired(version.getMinNorm() != null && version.getMaxNorm() != null);
        if (version.getUnitsPerProduct() != null) {
            operation.setUnitsPerProduct(version.getUnitsPerProduct());
        }
        operation.setNormDate(version.getNormDate());
    }

    private static boolean hasNorm(Operation operation) {
        return operation.getMinNorm() != null
                || operation.getMaxNorm() != null
                || operation.getUnitsPerProduct() != null
                || operation.getNormDate() != null;
    }

    private static boolean sameValues(OperationNormVersion version, Operation operation) {
        return Objects.equals(version.getMinNorm(), operation.getMinNorm())
                && Objects.equals(version.getMaxNorm(), operation.getMaxNorm())
                && Objects.equals(version.getUnitsPerProduct(), operation.getUnitsPerProduct())
                && Objects.equals(version.getNormDate(), operation.getNormDate());
    }
}
