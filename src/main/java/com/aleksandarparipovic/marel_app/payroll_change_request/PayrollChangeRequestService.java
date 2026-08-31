package com.aleksandarparipovic.marel_app.payroll_change_request;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemHandover;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asking payroll for a finished month back.
 *
 * <p><b>Why this workflow exists.</b> A submitted payroll used to be pullable
 * back by whoever held PAYROLL_HANDOVER — the supervisor who submitted it. That
 * is the wrong direction once payroll has begun working on the month: they
 * started from "this is finished", and taking it away underneath them silently
 * invalidates whatever they have done since. So the supervisor asks, with a
 * reason, and payroll answers.
 *
 * <p>Two states of the payroll can be asked about: submitted and locked. A
 * locked month is where an error is just as likely to be noticed, and accepting
 * takes it from LOCKED straight to DRAFT — one decision, one step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollChangeRequestService {

    /** The two the month can be asked back from. A DRAFT is already open. */
    private static final Set<String> REQUESTABLE_STATUSES = Set.of("APPROVED", "LOCKED");

    /** A page nobody asked for and nothing renders. */
    private static final int MAX_PAGE_SIZE = 200;

    private final PayrollChangeRequestRepository requestRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemService payrollRunItemService;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;
    private final OutboxEventPublisher outboxEventPublisher;

    // ── Asking ──────────────────────────────────────────────────────────────

    @Transactional
    public PayrollChangeRequestResponse create(PayrollChangeRequestCreateRequest request) {
        User requester = requireCurrentUser();

        PayrollRunItem item = payrollRunItemRepository.findById(request.getPayrollRunItemId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Obračun nije pronađen: " + request.getPayrollRunItemId()));

        if (!REQUESTABLE_STATUSES.contains(item.getStatus())) {
            throw new ConflictException(
                    "Izmena se traži samo za predat ili zaključan obračun. Trenutno stanje: "
                            + item.getStatus() + ".");
        }

        /*
         * One open request per payroll. uq_pcr_open_per_item is the real
         * guarantee — two supervisors can each pass this check and both insert —
         * and this exists so the ordinary case fails with a sentence somebody
         * can act on rather than a constraint name.
         */
        requestRepository
                .findByPayrollRunItem_IdAndStatus(item.getId(), PayrollChangeRequestStatus.PENDING)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "Za ovaj obračun već postoji zahtev za izmenu koji čeka odgovor.");
                });

        PayrollChangeRequest saved = requestRepository.save(PayrollChangeRequest.builder()
                .payrollRunItem(item)
                .requestedBy(requester)
                .reason(request.getReason().trim())
                .status(PayrollChangeRequestStatus.PENDING)
                .build());

        // On the payroll's own timeline as well as on the request, because that
        // is where somebody looks to find out why a finished month is in play.
        payrollRunItemService.recordChangeRequestStep(
                item.getId(), PayrollRunItemHandover.EVENT_CHANGE_REQUESTED, saved.getReason());

        publish(OutboxEventType.PAYROLL_CHANGE_REQUEST_CREATED, saved);

        log.info("Payroll change request {} raised on item {} by user {}",
                saved.getId(), item.getId(), requester.getId());

        return toResponse(requestRepository.findDetailById(saved.getId()).orElse(saved));
    }

    // ── Answering ───────────────────────────────────────────────────────────

    /**
     * Grants it: the payroll goes back to the supervisor.
     *
     * <p>The status move and the decision are one transaction. A request marked
     * accepted beside a payroll that never reopened would be the worst of the
     * three possible outcomes — everybody would believe the month was theirs
     * again.
     */
    @Transactional
    public PayrollChangeRequestResponse accept(Long requestId, String note) {
        PayrollChangeRequest request = loadPending(requestId);
        User decider = requireCurrentUser();

        request.decide(PayrollChangeRequestStatus.ACCEPTED, decider, note);

        payrollRunItemService.reopenForChangeRequest(
                request.getPayrollRunItem().getId(), note);

        publish(OutboxEventType.PAYROLL_CHANGE_REQUEST_ACCEPTED, request);

        log.info("Payroll change request {} accepted by user {}", requestId, decider.getId());
        return toResponse(requestRepository.findDetailById(requestId).orElse(request));
    }

    /** Refuses it. The payroll does not move, and the reason is on the row. */
    @Transactional
    public PayrollChangeRequestResponse decline(Long requestId, String note) {
        PayrollChangeRequest request = loadPending(requestId);
        User decider = requireCurrentUser();

        request.decide(PayrollChangeRequestStatus.DECLINED, decider, note);

        payrollRunItemService.recordChangeRequestStep(
                request.getPayrollRunItem().getId(),
                PayrollRunItemHandover.EVENT_CHANGE_DECLINED,
                request.getDecisionNote());

        publish(OutboxEventType.PAYROLL_CHANGE_REQUEST_DECLINED, request);

        log.info("Payroll change request {} declined by user {}", requestId, decider.getId());
        return toResponse(requestRepository.findDetailById(requestId).orElse(request));
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * One page of what the requests screen may show this reader.
     *
     * <p>Whoever ANSWERS these sees the queue; everybody else sees their own.
     * Narrowed HERE rather than by the screen, so a supervisor cannot read the
     * queue by asking for it directly.
     *
     * <p>Paged on the server, like every other request type. The screen shows
     * five of a status and asks for more; it never receives the lot and shortens
     * it in the browser, which stops being the truth the moment there are more
     * than fit on a page.
     *
     * @param status null for every status
     */
    @Transactional(readOnly = true)
    public Page<PayrollChangeRequestResponse> search(
            PayrollChangeRequestStatus status, int page, int size
    ) {
        Long restrictTo =
                permissionService.hasPermission(AppPermission.PAYROLL_CHANGE_REQUEST_PROCESS)
                        ? null
                        : currentUserService.getCurrentUserId();

        /*
         * A caller who may neither answer these nor be identified has none of
         * their own to see. Returning an empty page rather than every row is the
         * difference between "you have no requests" and a leak.
         */
        if (restrictTo == null
                && !permissionService.hasPermission(AppPermission.PAYROLL_CHANGE_REQUEST_PROCESS)) {
            return Page.empty();
        }

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        return requestRepository.search(status, restrictTo, pageable)
                .map(this::toResponse);
    }

    /** What one payroll's own page shows beside its history. */
    @Transactional(readOnly = true)
    public List<PayrollChangeRequestResponse> forPayrollRunItem(Long payrollRunItemId) {
        return requestRepository
                .findByPayrollRunItem_IdOrderByRequestedAtDesc(payrollRunItemId)
                .stream().map(this::toResponse).toList();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private PayrollChangeRequest loadPending(Long requestId) {
        PayrollChangeRequest request = requestRepository.findDetailById(requestId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Zahtev nije pronađen: " + requestId));

        if (!request.isPending()) {
            throw new ConflictException("Na ovaj zahtev je već odgovoreno.");
        }
        return request;
    }

    private User requireCurrentUser() {
        Long userId = currentUserService.getCurrentUserId();
        if (userId == null) {
            throw new ConflictException("Zahtev može da podnese samo prijavljeni korisnik.");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    /**
     * Announces the request or its answer.
     *
     * <p>The payload carries who to tell — the requester on a decision, and
     * nothing on creation, where fan-out resolves everybody who may answer from
     * the permission. Read from the payload rather than from the security
     * context, because fan-out runs on a worker thread long after the request
     * that caused it is gone.
     */
    private void publish(OutboxEventType type, PayrollChangeRequest request) {
        PayrollRunItem item = request.getPayrollRunItem();

        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("payrollRunItemId", item.getId());
        payload.put("period", item.getPeriod() == null ? null : item.getPeriod().toString());
        payload.put("employeeName", item.getEmployee() == null
                ? null : item.getEmployee().getFullName());
        payload.put("requestedByUserId", request.getRequestedBy().getId());
        payload.put("actorUserId", currentUserService.getCurrentUserId());

        outboxEventPublisher.publish(
                type, OutboxAggregateType.PAYROLL_CHANGE_REQUEST, request.getId(), payload);
    }

    private PayrollChangeRequestResponse toResponse(PayrollChangeRequest r) {
        PayrollRunItem item = r.getPayrollRunItem();

        return new PayrollChangeRequestResponse(
                r.getId(),
                item.getId(),
                item.getMonthlyReport() == null ? null : item.getMonthlyReport().getId(),
                item.getMonthlyReport() == null || item.getMonthlyReport().getEmployeeRecord() == null
                        ? null : item.getMonthlyReport().getEmployeeRecord().getId(),
                item.getEmployee() == null ? null : item.getEmployee().getId(),
                item.getEmployee() == null ? null : item.getEmployee().getFullName(),
                item.getPeriod(),
                item.getStatus(),
                r.getRequestedBy().getId(),
                r.getRequestedBy().getFullName(),
                r.getRequestedAt(),
                r.getReason(),
                r.getStatus(),
                r.getDecidedBy() == null ? null : r.getDecidedBy().getId(),
                r.getDecidedBy() == null ? null : r.getDecidedBy().getFullName(),
                r.getDecidedAt(),
                r.getDecisionNote()
        );
    }
}
