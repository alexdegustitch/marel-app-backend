package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.production_order_progress.OrderProgressService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormActivationDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationOrderUsageRow;
import com.aleksandarparipovic.marel_app.operation.dto.OperationOutputPointDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWorkLogRow;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormActivation;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormInForceService;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormActivationRepository;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormVersion;
import com.aleksandarparipovic.marel_app.operation_norm_version.OperationNormVersionRepository;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.sample_order_line_item.repository.SampleOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the operation's own page asks for: its norm history, where the
 * operation is used, who worked it, and how much it produced.
 *
 * <p>Kept apart from {@link OperationService}, which owns creating and editing
 * an operation. This class only reads — with two exceptions that belong to the
 * norm history itself: adding a norm and verifying one.
 */
@Service
@RequiredArgsConstructor
public class OperationDetailService {

    /** How many months each period on the statistics chart covers. */
    public static final int MIN_MONTHS = 1;
    public static final int MAX_MONTHS = 24;

    private final OperationRepository operationRepository;
    private final OperationNormVersionRepository normVersionRepository;
    private final OperationNormActivationRepository normActivationRepository;
    private final OperationNormInForceService normInForce;
    private final ProductionOrderLineItemRepository productionOrderLineItemRepository;
    private final SampleOrderLineItemRepository sampleOrderLineItemRepository;
    private final WorkLogRepository workLogRepository;
    private final UserRepository userRepository;
    private final OrderProgressService orderProgressService;

    // ── Norm history ────────────────────────────────────────────────────────

    /**
     * The operation's norms, newest entry first.
     *
     * @param includeArchived whether archived norms are part of the answer. They
     *   are still history — what an older payroll was calculated against — so the
     *   screen offers them behind a switch rather than dropping them.
     */
    @Transactional(readOnly = true)
    public List<OperationNormVersionDto> getNormHistory(Long operationId, boolean includeArchived) {
        requireOperation(operationId);
        List<OperationNormVersion> versions = includeArchived
                ? normVersionRepository.findByOperation_IdOrderByCreatedAtDescIdDesc(operationId)
                : normVersionRepository.findByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(operationId);

        Map<Long, OperationNormActivation> lastActivation = lastActivationPerVersion(operationId);

        List<OperationNormVersionDto> history = new ArrayList<>(versions.size());
        for (OperationNormVersion version : versions) {
            history.add(toDto(version, lastActivation.get(version.getId())));
        }
        return history;
    }

    /**
     * The chronology of which norm the operation worked to.
     *
     * <p>Entries are append-only, so this reads as what happened. The end of an
     * entry is derived here — an entry ends where the next one begins, and the
     * newest one ends when its norm was archived, or not at all.
     */
    @Transactional(readOnly = true)
    public List<OperationNormActivationDto> getNormActivations(Long operationId) {
        requireOperation(operationId);
        List<OperationNormActivation> entries =
                normActivationRepository.findByOperation_IdOrderByActivatedAtDescIdDesc(operationId);

        List<OperationNormActivationDto> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            OperationNormActivation entry = entries.get(i);
            OperationNormVersion version = entry.getNormVersion();
            // Newest first, so the entry that ENDS this one is the one before it.
            OffsetDateTime until = i == 0 ? version.getArchivedAt() : entries.get(i - 1).getActivatedAt();
            rows.add(new OperationNormActivationDto(
                    entry.getId(),
                    version.getId(),
                    version.getMinNorm(),
                    entry.getActivatedAt(),
                    until,
                    entry.getActivatedBy() == null ? null : entry.getActivatedBy().getFullName(),
                    entry.getReason(),
                    entry.getSource().name()
            ));
        }
        return rows;
    }

    /**
     * Records a new norm and puts it in force.
     *
     * <p>Both halves matter. The version is the history; the columns on the
     * operation are what payroll and the manufacturing-time report read, so a
     * new norm that did not update them would be a norm nobody works to.
     */
    @Transactional
    public OperationNormVersionDto addNorm(
            Long operationId,
            OperationNormVersionCreateRequest request,
            Authentication authentication
    ) {
        Operation operation = requireOperation(operationId);
        validate(request);

        // Without a norm there is nothing for a date to date, and nothing for
        // "privremena" to say — the screen disables both, and the server does not
        // depend on the screen for that.
        boolean temporary = request.norm() != null && Boolean.TRUE.equals(request.temporary());
        LocalDate normDate = request.norm() == null || temporary ? null : request.normDate();

        OperationNormVersion version = OperationNormVersion.builder()
                .operation(operation)
                .minNorm(request.norm())
                .maxNorm(request.norm())
                .unitsPerProduct(request.unitsPerProduct())
                .normDate(normDate)
                .temporary(temporary)
                .note(request.note())
                .createdBy(currentUser(authentication))
                .build();
        version = normVersionRepository.saveAndFlush(version);

        makeCurrent(operation, version, currentUser(authentication), null,
                OperationNormActivation.Source.ADDED);
        return toDto(version, lastActivationPerVersion(operationId).get(version.getId()));
    }

    /**
     * Edits the norm in force. Only that one: an older version is what an older
     * payroll was calculated against, and rewriting it would rewrite history.
     */
    @Transactional
    public OperationNormVersionDto updateNorm(
            Long operationId,
            Long versionId,
            OperationNormVersionCreateRequest request,
            Authentication authentication
    ) {
        Operation operation = requireOperation(operationId);
        validate(request);

        OperationNormVersion version = requireVersion(operationId, versionId);
        if (!version.isCurrent()) {
            throw new IllegalStateException("Može se menjati samo važeća norma");
        }

        boolean temporary = request.norm() != null && Boolean.TRUE.equals(request.temporary());
        version.setMinNorm(request.norm());
        version.setMaxNorm(request.norm());
        version.setUnitsPerProduct(request.unitsPerProduct());
        version.setNormDate(request.norm() == null || temporary ? null : request.normDate());
        version.setTemporary(temporary);
        version.setNote(request.note());

        // The values the operation works to changed, even though which version is
        // in force did not — without an entry the chronology would say the shop
        // floor has been working to this number since before it existed.
        makeCurrent(operation, version, currentUser(authentication), null,
                OperationNormActivation.Source.EDITED);
        return toDto(version, lastActivationPerVersion(operationId).get(version.getId()));
    }

    /**
     * Puts a norm from the history back in force.
     *
     * <p>Including an archived one: archiving takes a norm off the list, it does
     * not deny that the factory may go back to it, so restoring un-archives it in
     * the same transaction. Doing this to the norm already in force is a no-op
     * rather than an error, the same way verifying twice is.
     */
    @Transactional
    public OperationNormVersionDto activateNorm(
            Long operationId,
            Long versionId,
            String reason,
            Authentication authentication
    ) {
        Operation operation = requireOperation(operationId);
        OperationNormVersion version = requireVersion(operationId, versionId);

        if (version.isCurrent()) {
            return toDto(version, lastActivationPerVersion(operationId).get(version.getId()));
        }

        version.setArchivedAt(null);
        makeCurrent(operation, version, currentUser(authentication), reason,
                OperationNormActivation.Source.ACTIVATED);
        return toDto(version, lastActivationPerVersion(operationId).get(version.getId()));
    }

    /**
     * Archives the norm in force, and the one that would apply next takes over —
     * so the operation is left working to a norm somebody actually recorded, not
     * to no norm at all. With nothing to inherit the operation is left un-normed,
     * which is a state the schema already allows.
     */
    @Transactional
    public void archiveNorm(Long operationId, Long versionId, Authentication authentication) {
        Operation operation = requireOperation(operationId);
        OperationNormVersion version = requireVersion(operationId, versionId);
        if (!version.isCurrent()) {
            throw new IllegalStateException("Može se arhivirati samo važeća norma");
        }

        version.setArchivedAt(OffsetDateTime.now());
        // Cleared before the successor claims it: at most one norm per operation
        // may be in force, and the database enforces exactly that.
        version.setCurrent(false);
        normVersionRepository.saveAndFlush(version);

        OperationNormVersion successor = normVersionRepository
                .findSuccessionCandidates(operationId, versionId)
                .stream()
                .findFirst()
                .orElse(null);

        if (successor == null) {
            operation.setMinNorm(null);
            operation.setMaxNorm(null);
            operation.setNormRequired(false);
            operation.setNormDate(null);
            return;
        }

        makeCurrent(operation, successor, currentUser(authentication),
                "Nasleđena pošto je prethodna norma arhivirana",
                OperationNormActivation.Source.SUCCEEDED);
    }

    /** Signs off a norm version. Verifying twice is a no-op, not an error. */
    @Transactional
    public OperationNormVersionDto verifyNorm(Long operationId, Long versionId, Authentication authentication) {
        requireOperation(operationId);
        OperationNormVersion version = requireVersion(operationId, versionId);

        if (!version.isVerified()) {
            version.setVerifiedBy(currentUser(authentication));
            version.setVerifiedAt(OffsetDateTime.now());
        }

        return toDto(version, lastActivationPerVersion(operationId).get(version.getId()));
    }

    /**
     * Delegates to the one writer of "which norm is in force".
     *
     * <p>It lives in {@link OperationNormInForceService} rather than here because
     * the operation FORM writes the same fact from the other direction, and two
     * copies of this would be two ways for the flag, the chronology and the
     * operation's own columns to drift apart.
     */
    private void makeCurrent(
            Operation operation,
            OperationNormVersion version,
            User by,
            String reason,
            OperationNormActivation.Source source
    ) {
        normInForce.putInForce(operation, version, by, reason, source);
    }

    /** The most recent decision per version — what "in force since" reads from. */
    private Map<Long, OperationNormActivation> lastActivationPerVersion(Long operationId) {
        Map<Long, OperationNormActivation> byVersion = new HashMap<>();
        // Newest first, so the first entry seen for a version is its latest.
        for (OperationNormActivation entry :
                normActivationRepository.findByOperation_IdOrderByActivatedAtDescIdDesc(operationId)) {
            byVersion.putIfAbsent(entry.getNormVersion().getId(), entry);
        }
        return byVersion;
    }

    private static void validate(OperationNormVersionCreateRequest request) {
        if (request.norm() != null && request.norm() <= 0) {
            throw new IllegalArgumentException("Norma mora biti veća od nule");
        }
        if (request.unitsPerProduct() != null && request.unitsPerProduct() <= 0) {
            throw new IllegalArgumentException("Količina u sklopu mora biti veća od nule");
        }
        if (Boolean.TRUE.equals(request.temporary()) && request.normDate() != null) {
            throw new IllegalArgumentException("Privremena norma se unosi bez datuma");
        }
        // A norm is optional. A DATE without one is not refused but dropped: the
        // caller asked for no norm, and a date is only ever a norm's date.
    }

    private OperationNormVersion requireVersion(Long operationId, Long versionId) {
        OperationNormVersion version = normVersionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Norm version not found"));
        if (!version.getOperation().getId().equals(operationId)) {
            throw new IllegalArgumentException("Norma ne pripada ovoj operaciji");
        }
        return version;
    }

    // ── Where the operation is used ─────────────────────────────────────────

    /**
     * The production orders this operation is worked for, with progress.
     *
     * <p>An operation belongs to a product, and a production order carries a
     * quantity of that product — so the pieces required are that quantity times
     * how many of this operation one product needs.
     *
     * <p>How many that is comes from the ORDER'S agreed scope where one exists,
     * and from the catalogue otherwise. The scope is the better answer and the
     * whole reason it is written down: it is the floor saying what THIS order's
     * variant actually needs, which is not always what the catalogue lists. When
     * neither can say, the requirement stays null rather than being guessed.
     */
    @Transactional(readOnly = true)
    public List<OperationOrderUsageRow> getProductionOrders(Long operationId) {
        Operation operation = requireOperation(operationId);
        Integer unitsPerProduct = operation.getUnitsPerProduct();

        Map<Long, Long> doneByOrder = new HashMap<>();
        workLogRepository.sumOutputPerOrderForOperation(operationId)
                .forEach(row -> doneByOrder.put(row.getOrderId(), row.getQuantity()));

        Map<Long, Long> agreed = orderProgressService.agreedRequirementForOperation(operationId);

        return productionOrderLineItemRepository
                .findOrderRowsByProductId(operation.getProduct().getId())
                .stream()
                .map(row -> {
                    Long fromScope = agreed.get(row.orderId());
                    return new OperationOrderUsageRow(
                            row.orderId(),
                            row.code(),
                            row.name(),
                            row.status(),
                            row.orderDate(),
                            row.deliveryDeadline(),
                            row.quantity(),
                            fromScope != null
                                    ? Math.toIntExact(fromScope)
                                    : requiredPieces(row.quantity(), unitsPerProduct),
                            Math.toIntExact(doneByOrder.getOrDefault(row.orderId(), 0L)),
                            fromScope != null);
                })
                .toList();
    }

    /**
     * The sample orders the operation's product appears on.
     *
     * <p>No progress: work logs are recorded against production orders only, so
     * there is no way to say how many pieces of this operation a sample order
     * has consumed. Showing a zero there would be a claim, not a measurement.
     */
    @Transactional(readOnly = true)
    public List<ProductSampleOrderRow> getSampleOrders(Long operationId) {
        Operation operation = requireOperation(operationId);
        return sampleOrderLineItemRepository.findOrderRowsByProductId(operation.getProduct().getId());
    }

    // ── What was actually worked ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OperationWorkLogRow> getRecentWorkLogs(Long operationId, int limit) {
        requireOperation(operationId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return workLogRepository.findRecentWorkForOperation(operationId, safeLimit)
                .stream()
                .map(row -> new OperationWorkLogRow(
                        row.getWorkLogId(),
                        row.getEmployeeId(),
                        row.getEmployeeName(),
                        row.getWorkDate(),
                        // The instant, expressed in UTC. The screen renders it in
                        // the reader's own zone, so the offset carried here only
                        // has to be truthful, not local.
                        atUtc(row.getStartAt()),
                        atUtc(row.getEndAt()),
                        row.getDurationMin(),
                        row.getQuantity(),
                        row.getScrap(),
                        row.getOrderId(),
                        row.getOrderCode()
                ))
                .toList();
    }

    /** How many days the one-month view of the chart covers. */
    public static final int DAILY_WINDOW_DAYS = 30;

    /**
     * Output over the last {@code months} months, INCLUDING the buckets with no
     * work at all — a gap on a chart reads as missing data, while a zero reads
     * as what it is: nothing was produced.
     *
     * <p>One month is served DAY by day. A single bar for a whole month answers
     * nothing about a month; the question "how did the last month go" is a
     * question about its days.
     */
    @Transactional(readOnly = true)
    public List<OperationOutputPointDto> getMonthlyOutput(Long operationId, int months) {
        requireOperation(operationId);
        int safeMonths = Math.max(MIN_MONTHS, Math.min(months, MAX_MONTHS));

        if (safeMonths == 1) {
            return dailyOutput(operationId);
        }

        Map<String, long[]> byMonth = new HashMap<>();
        workLogRepository.monthlyOutputForOperation(operationId, safeMonths).forEach(row ->
                byMonth.put(row.getPeriod(), new long[]{row.getQuantity(), row.getScrap()}));

        List<OperationOutputPointDto> points = new ArrayList<>(safeMonths);
        YearMonth start = YearMonth.from(LocalDate.now()).minusMonths(safeMonths - 1L);
        for (int i = 0; i < safeMonths; i++) {
            String key = start.plusMonths(i).toString();
            long[] values = byMonth.getOrDefault(key, new long[]{0L, 0L});
            points.add(new OperationOutputPointDto(key, values[0], values[1]));
        }
        return points;
    }

    private List<OperationOutputPointDto> dailyOutput(Long operationId) {
        Map<String, long[]> byDay = new HashMap<>();
        workLogRepository.dailyOutputForOperation(operationId, DAILY_WINDOW_DAYS).forEach(row ->
                byDay.put(row.getPeriod(), new long[]{row.getQuantity(), row.getScrap()}));

        List<OperationOutputPointDto> points = new ArrayList<>(DAILY_WINDOW_DAYS);
        LocalDate start = LocalDate.now().minusDays(DAILY_WINDOW_DAYS - 1L);
        for (int i = 0; i < DAILY_WINDOW_DAYS; i++) {
            String key = start.plusDays(i).toString();
            long[] values = byDay.getOrDefault(key, new long[]{0L, 0L});
            points.add(new OperationOutputPointDto(key, values[0], values[1]));
        }
        return points;
    }

    // ── May this operation be archived? ─────────────────────────────────────

    /**
     * What stands in the way of archiving this operation, in words the screen
     * can print. An empty list means it may be archived.
     *
     * <p>The rule: no LIVE order may still owe pieces of this operation. For a
     * production order that is measurable — required versus done. For a sample
     * order it is not: work is logged against production orders only, so an
     * open sample order blocks on its status alone. An active production order
     * whose requirement cannot be computed (the operation does not say how many
     * pieces one product needs) also blocks — "unknown" is not "finished".
     */
    @Transactional(readOnly = true)
    public List<String> getArchiveBlockers(Long operationId) {
        List<String> blockers = new ArrayList<>();

        for (OperationOrderUsageRow order : getProductionOrders(operationId)) {
            if (order.status() == ProductionOrderStatus.DELIVERED) {
                continue;
            }
            if (order.requiredPieces() == null) {
                blockers.add("Nalog %s: nije poznato koliko komada je potrebno (operacija nema količinu u sklopu)"
                        .formatted(order.code()));
            } else if (order.donePieces() == null || order.donePieces() < order.requiredPieces()) {
                int done = order.donePieces() == null ? 0 : order.donePieces();
                blockers.add("Nalog %s: urađeno %d od %d komada"
                        .formatted(order.code(), done, order.requiredPieces()));
            }
        }

        for (ProductSampleOrderRow sample : getSampleOrders(operationId)) {
            if (!CLOSED_SAMPLE_STATUS.equalsIgnoreCase(sample.status())) {
                blockers.add("Nalog za uzorak „%s“ nije zatvoren".formatted(sample.name()));
            }
        }

        return blockers;
    }

    /**
     * The one sample-order status that means the work is over. Sample-order
     * status is a free-form column, so only the value the schema evidences
     * (`closed`, alongside the `closed_by` actor) is treated as final.
     */
    private static final String CLOSED_SAMPLE_STATUS = "closed";

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static OffsetDateTime atUtc(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private static Integer requiredPieces(Integer orderedQuantity, Integer unitsPerProduct) {
        if (orderedQuantity == null || unitsPerProduct == null) {
            return null;
        }
        return orderedQuantity * unitsPerProduct;
    }

    private Operation requireOperation(Long operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return userRepository.findByUsername(authentication.getName()).orElse(null);
    }

    private static OperationNormVersionDto toDto(OperationNormVersion version, OperationNormActivation lastActivation) {
        return new OperationNormVersionDto(
                version.getId(),
                version.getMinNorm(),
                version.getMaxNorm(),
                version.getUnitsPerProduct(),
                version.getNormDate(),
                version.getNote(),
                version.getCreatedAt(),
                version.getCreatedBy() == null ? null : version.getCreatedBy().getFullName(),
                version.getVerifiedAt(),
                version.getVerifiedBy() == null ? null : version.getVerifiedBy().getFullName(),
                version.isCurrent(),
                version.isTemporary(),
                version.getArchivedAt(),
                lastActivation == null ? null : lastActivation.getActivatedAt(),
                lastActivation == null || lastActivation.getActivatedBy() == null
                        ? null : lastActivation.getActivatedBy().getFullName()
        );
    }
}
