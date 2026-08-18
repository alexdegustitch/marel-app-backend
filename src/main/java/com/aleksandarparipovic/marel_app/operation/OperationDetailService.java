package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionCreateRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationNormVersionDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationOrderUsageRow;
import com.aleksandarparipovic.marel_app.operation.dto.OperationOutputPointDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWorkLogRow;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
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
    private final ProductionOrderLineItemRepository productionOrderLineItemRepository;
    private final SampleOrderLineItemRepository sampleOrderLineItemRepository;
    private final WorkLogRepository workLogRepository;
    private final UserRepository userRepository;

    // ── Norm history ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OperationNormVersionDto> getNormHistory(Long operationId) {
        requireOperation(operationId);
        List<OperationNormVersion> versions =
                normVersionRepository.findByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(operationId);

        List<OperationNormVersionDto> history = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            history.add(toDto(versions.get(i), i == 0));
        }
        return history;
    }

    /**
     * Records a new norm and makes it the current one.
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

        OperationNormVersion version = OperationNormVersion.builder()
                .operation(operation)
                .minNorm(request.norm())
                .maxNorm(request.norm())
                .unitsPerProduct(request.unitsPerProduct())
                .normDate(request.normDate())
                .note(request.note())
                .createdBy(currentUser(authentication))
                .build();
        version = normVersionRepository.save(version);

        applyToOperation(operation, version);
        return toDto(version, true);
    }

    /**
     * Edits the norm in force. Only that one: an older version is what an older
     * payroll was calculated against, and rewriting it would rewrite history.
     */
    @Transactional
    public OperationNormVersionDto updateNorm(
            Long operationId,
            Long versionId,
            OperationNormVersionCreateRequest request
    ) {
        Operation operation = requireOperation(operationId);
        validate(request);

        OperationNormVersion version = requireVersion(operationId, versionId);
        requireCurrent(operationId, version, "Može se menjati samo važeća norma");

        version.setMinNorm(request.norm());
        version.setMaxNorm(request.norm());
        version.setUnitsPerProduct(request.unitsPerProduct());
        version.setNormDate(request.normDate());
        version.setNote(request.note());

        applyToOperation(operation, version);
        return toDto(version, true);
    }

    /**
     * Archives the norm in force, and the one before it becomes current again —
     * so the operation is left working to a norm somebody actually recorded,
     * not to no norm at all. With no earlier version the operation is left
     * un-normed, which is a state the schema already allows.
     */
    @Transactional
    public void archiveNorm(Long operationId, Long versionId) {
        Operation operation = requireOperation(operationId);
        OperationNormVersion version = requireVersion(operationId, versionId);
        requireCurrent(operationId, version, "Može se arhivirati samo važeća norma");

        version.setArchivedAt(OffsetDateTime.now());

        OperationNormVersion previous = normVersionRepository
                .findFirstByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(operationId)
                .orElse(null);

        if (previous == null) {
            operation.setMinNorm(null);
            operation.setMaxNorm(null);
            operation.setNormRequired(false);
            operation.setNormDate(null);
        } else {
            applyToOperation(operation, previous);
        }
    }

    /**
     * Copies a version onto the operation's own columns — what payroll and the
     * manufacturing-time report read. A norm that did not land here would be a
     * norm nobody works to.
     */
    private static void applyToOperation(Operation operation, OperationNormVersion version) {
        operation.setMinNorm(version.getMinNorm());
        operation.setMaxNorm(version.getMaxNorm());
        // The database CHECK ties norm_required to the pair being present.
        operation.setNormRequired(version.getMinNorm() != null && version.getMaxNorm() != null);
        if (version.getUnitsPerProduct() != null) {
            operation.setUnitsPerProduct(version.getUnitsPerProduct());
        }
        operation.setNormDate(version.getNormDate());
    }

    private static void validate(OperationNormVersionCreateRequest request) {
        if (request.norm() != null && request.norm() <= 0) {
            throw new IllegalArgumentException("Norma mora biti veća od nule");
        }
        if (request.unitsPerProduct() != null && request.unitsPerProduct() <= 0) {
            throw new IllegalArgumentException("Količina u sklopu mora biti veća od nule");
        }
    }

    private OperationNormVersion requireVersion(Long operationId, Long versionId) {
        OperationNormVersion version = normVersionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Norm version not found"));
        if (!version.getOperation().getId().equals(operationId)) {
            throw new IllegalArgumentException("Norma ne pripada ovoj operaciji");
        }
        return version;
    }

    private void requireCurrent(Long operationId, OperationNormVersion version, String message) {
        OperationNormVersion current = normVersionRepository
                .findFirstByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(operationId)
                .orElse(null);
        if (current == null || !current.getId().equals(version.getId())) {
            throw new IllegalStateException(message);
        }
    }

    /** Signs off a norm version. Verifying twice is a no-op, not an error. */
    @Transactional
    public OperationNormVersionDto verifyNorm(Long operationId, Long versionId, Authentication authentication) {
        requireOperation(operationId);

        OperationNormVersion version = normVersionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Norm version not found"));

        if (!version.getOperation().getId().equals(operationId)) {
            throw new IllegalArgumentException("Norma ne pripada ovoj operaciji");
        }

        if (!version.isVerified()) {
            version.setVerifiedBy(currentUser(authentication));
            version.setVerifiedAt(OffsetDateTime.now());
        }

        OperationNormVersion current = normVersionRepository
                .findFirstByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(operationId)
                .orElse(null);
        return toDto(version, current != null && current.getId().equals(version.getId()));
    }

    // ── Where the operation is used ─────────────────────────────────────────

    /**
     * The production orders this operation is worked for, with progress.
     *
     * <p>An operation belongs to a product, and a production order carries a
     * quantity of that product — so the pieces required are that quantity times
     * how many of this operation one product needs. When the operation does not
     * say how many that is, the requirement is left null rather than guessed.
     */
    @Transactional(readOnly = true)
    public List<OperationOrderUsageRow> getProductionOrders(Long operationId) {
        Operation operation = requireOperation(operationId);
        Integer unitsPerProduct = operation.getUnitsPerProduct();

        Map<Long, Long> doneByOrder = new HashMap<>();
        workLogRepository.sumOutputPerOrderForOperation(operationId)
                .forEach(row -> doneByOrder.put(row.getOrderId(), row.getQuantity()));

        return productionOrderLineItemRepository
                .findOrderRowsByProductId(operation.getProduct().getId())
                .stream()
                .map(row -> new OperationOrderUsageRow(
                        row.orderId(),
                        row.code(),
                        row.name(),
                        row.status(),
                        row.orderDate(),
                        row.deliveryDeadline(),
                        row.quantity(),
                        requiredPieces(row.quantity(), unitsPerProduct),
                        Math.toIntExact(doneByOrder.getOrDefault(row.orderId(), 0L))
                ))
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

    private static OperationNormVersionDto toDto(OperationNormVersion version, boolean current) {
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
                current
        );
    }
}
