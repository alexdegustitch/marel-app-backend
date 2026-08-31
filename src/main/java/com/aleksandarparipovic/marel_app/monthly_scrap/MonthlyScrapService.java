package com.aleksandarparipovic.marel_app.monthly_scrap;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapResponse;
import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapSaveRequest;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Counting the scrap nobody reported, one month at a time.
 *
 * <p>Two rules live here and nowhere else:
 *
 * <ol>
 *   <li><b>The month is the screen's, not the client's.</b> Every write takes
 *       year and month from the request path and builds the first of that month
 *       itself. A period sent in a body could file a count under a month that is
 *       already closed, and nothing downstream would notice.
 *   <li><b>The operation's product is the row's product.</b> The composite
 *       foreign key already refuses a mismatch, but it refuses it as a constraint
 *       violation. Checked here so the person entering it reads a sentence.
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class MonthlyScrapService {

    private final MonthlyScrapRepository monthlyScrapRepository;
    private final OperationRepository operationRepository;
    private final EntityReferenceProvider referenceProvider;

    @Transactional(readOnly = true)
    public List<MonthlyScrapResponse> findForMonth(int year, int month) {
        return monthlyScrapRepository.findActiveForPeriod(periodOf(year, month))
                .stream().map(MonthlyScrapResponse::new).toList();
    }

    @Transactional
    public MonthlyScrapResponse create(int year, int month, MonthlyScrapSaveRequest request) {
        MonthlyScrap scrap = new MonthlyScrap();
        scrap.setPeriod(periodOf(year, month));
        scrap.setIsActive(true);
        apply(scrap, request);

        MonthlyScrap saved = monthlyScrapRepository.save(scrap);
        return reload(saved.getId());
    }

    /**
     * Replace one row. The whole row arrives every time — see the request DTO for
     * why this is not a patch.
     */
    @Transactional
    public MonthlyScrapResponse update(Long id, MonthlyScrapSaveRequest request) {
        MonthlyScrap scrap = require(id);
        apply(scrap, request);
        monthlyScrapRepository.save(scrap);
        return reload(id);
    }

    /**
     * Take a row back without losing that it was there.
     *
     * <p>Deactivation rather than DELETE: {@code set_archived_at_on_deactivate}
     * stamps the moment, the audit trigger records who, and a count somebody
     * removed stays answerable weeks later. The list only reads active rows, so
     * to the screen it is gone.
     */
    @Transactional
    public void delete(Long id) {
        MonthlyScrap scrap = require(id);
        if (Boolean.FALSE.equals(scrap.getIsActive())) {
            return;
        }
        scrap.setIsActive(false);
        // The trigger stamps this too; set here so the entity in hand agrees
        // with the row, rather than reading null until the next load.
        if (scrap.getArchivedAt() == null) {
            scrap.setArchivedAt(OffsetDateTime.now());
        }
        monthlyScrapRepository.save(scrap);
    }

    // ── shared ───────────────────────────────────────────────────────────────

    private void apply(MonthlyScrap scrap, MonthlyScrapSaveRequest request) {
        Operation operation = operationRepository.findById(request.getOperationId())
                .orElseThrow(() -> new EntityNotFoundException("Operacija nije pronađena."));

        requireOperationBelongsToProduct(operation, request.getProductId());

        scrap.setOperation(operation);
        scrap.setProduct(referenceProvider.getRequiredReference(
                Product.class, request.getProductId(), "productId"));
        scrap.setProductionOrder(referenceProvider.getOptionalReference(
                ProductionOrder.class, request.getProductionOrderId()));
        scrap.setQuantity(request.getQuantity());
        scrap.setNote(blankToNull(request.getNote()));
    }

    private void requireOperationBelongsToProduct(Operation operation, Long productId) {
        Long operationProductId = operation.getProduct() == null ? null : operation.getProduct().getId();
        if (operationProductId == null || !operationProductId.equals(productId)) {
            throw new ConflictException(
                    "Izabrana operacija ne pripada izabranom proizvodu.");
        }
    }

    private MonthlyScrap require(Long id) {
        return monthlyScrapRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new EntityNotFoundException("Škart nije pronađen."));
    }

    private MonthlyScrapResponse reload(Long id) {
        return new MonthlyScrapResponse(require(id));
    }

    /**
     * The first of the month, which is the only period the table accepts
     * ({@code chk_monthly_scraps_period_month}).
     */
    private LocalDate periodOf(int year, int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Mesec mora biti između 1 i 12.");
        }
        return LocalDate.of(year, month, 1);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
