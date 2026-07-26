package com.aleksandarparipovic.marel_app.user_registration_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_registration_request.dto.RegistrationRequestResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * The registration approval workflow.
 *
 * <p>The invariant this service exists to protect: the registration request and
 * the user's account status are two halves of one decision and are always written
 * together, in one transaction. There is no code path that approves a request
 * without activating the user, or activates a user without closing the request.
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationRequestService {

    private final UserRegistrationRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    /**
     * Opens the pending request for a freshly created account.
     *
     * <p>MANDATORY propagation: this must run inside the caller's registration
     * transaction so that "user created" and "request opened" commit or roll back
     * as one. A user with no request would be permanently invisible to reviewers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserRegistrationRequest openFor(User user) {
        if (requestRepository.existsByUser_IdAndStatus(
                user.getId(), UserRegistrationRequestStatus.PENDING)) {
            // Also enforced by uq_user_registration_requests_one_pending; this check
            // exists to give a readable message instead of a constraint violation.
            throw new ConflictException("Za ovaj nalog već postoji zahtev koji čeka odobrenje.");
        }

        UserRegistrationRequest request = requestRepository.save(
                UserRegistrationRequest.builder()
                        .user(user)
                        .status(UserRegistrationRequestStatus.PENDING)
                        .build()
        );

        outboxEventPublisher.publish(
                OutboxEventType.USER_REGISTRATION_REQUESTED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                request.getId(),
                payloadFor(user)
        );

        return request;
    }

    /**
     * Approves the request and activates the account, atomically.
     *
     * <p>Concurrency: the request carries a {@code @Version}, so if two
     * administrators approve the same pending row at once, exactly one commits and
     * the other fails with an OptimisticLockingFailureException (HTTP 409). The
     * partial unique index guarantees there was only ever one pending row to race
     * over in the first place.
     */
    @Transactional
    public RegistrationRequestResponse approve(Long requestId, Long reviewerId, String note) {
        UserRegistrationRequest request = loadOrThrow(requestId);
        User reviewer = loadUserOrThrow(reviewerId);

        request.approve(reviewer, note);

        User applicant = request.getUser();
        applicant.setAccountStatus(UserAccountStatus.ACTIVE);
        // is_active and activated_at are derived by trg_00_users_account_status_sync;
        // mirrored here only so the in-memory entity matches what the database will hold.
        applicant.setActive(true);

        outboxEventPublisher.publish(
                OutboxEventType.USER_REGISTRATION_APPROVED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                request.getId(),
                payloadFor(applicant)
        );

        return toResponse(request);
    }

    /**
     * Declines the request and marks the account DECLINED, atomically.
     * The account is never archived by a decline — archiving is a separate act.
     */
    @Transactional
    public RegistrationRequestResponse decline(Long requestId, Long reviewerId, String note) {
        UserRegistrationRequest request = loadOrThrow(requestId);
        User reviewer = loadUserOrThrow(reviewerId);

        request.decline(reviewer, note);

        User applicant = request.getUser();
        applicant.setAccountStatus(UserAccountStatus.DECLINED);
        applicant.setActive(false);

        outboxEventPublisher.publish(
                OutboxEventType.USER_REGISTRATION_DECLINED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                request.getId(),
                payloadFor(applicant)
        );

        return toResponse(request);
    }

    /**
     * Withdraws a still-open request. Allowed for the applicant themselves or an
     * administrator; the account stays PENDING_APPROVAL rather than being declined,
     * because withdrawing is not a refusal and must not read like one in history.
     */
    @Transactional
    public RegistrationRequestResponse cancel(Long requestId, Long actorId, String note) {
        UserRegistrationRequest request = loadOrThrow(requestId);
        User actor = loadUserOrThrow(actorId);

        request.cancel(actor, note);
        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public Page<RegistrationRequestResponse> list(
            UserRegistrationRequestStatus status, Pageable pageable
    ) {
        return requestRepository.findPageByStatus(status, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<RegistrationRequestResponse> listForUser(Long userId, Pageable pageable) {
        return requestRepository.findByUser_IdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public RegistrationRequestResponse getById(Long requestId) {
        return toResponse(loadOrThrow(requestId));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return requestRepository.countByStatus(UserRegistrationRequestStatus.PENDING);
    }

    private UserRegistrationRequest loadOrThrow(Long requestId) {
        return requestRepository.findDetailById(requestId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Zahtev za registraciju nije pronađen: " + requestId));
    }

    private User loadUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    /** Display-only metadata for the notification. No credentials, no tokens. */
    private Map<String, Object> payloadFor(User user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", user.getId());
        payload.put("username", user.getUsername());
        payload.put("fullName", user.getFullName());
        return payload;
    }

    private RegistrationRequestResponse toResponse(UserRegistrationRequest request) {
        User applicant = request.getUser();
        User reviewer = request.getReviewedBy();

        return new RegistrationRequestResponse(
                request.getId(),
                applicant.getId(),
                applicant.getUsername(),
                applicant.getFullName(),
                applicant.getEmailAddress(),
                applicant.getMobilePhone(),
                applicant.getRole() == null ? null : applicant.getRole().getRoleName(),
                request.getStatus(),
                request.getReviewNote(),
                reviewer == null ? null : reviewer.getId(),
                reviewer == null ? null : reviewer.getFullName(),
                request.getReviewedAt(),
                request.getCreatedAt()
        );
    }
}
