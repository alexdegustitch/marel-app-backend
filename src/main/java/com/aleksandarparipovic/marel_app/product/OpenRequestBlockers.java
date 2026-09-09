package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequest;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestRepository;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestStatus;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequest;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestRepository;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Archive blockers that come from OPEN requests, shared by the product and the
 * operation archive checks.
 *
 * <p>The rule: while a request for an order's razrada or for a manufacturing
 * time is still moving — waiting to be taken ("čeka na obradu") or already
 * claimed ("preuzet") — nothing it covers may be archived. Whoever processes
 * that request is about to write an answer about this product and its
 * operations, and archiving the subject from under them would make the answer
 * land on an archived thing. A request that reached a terminal state
 * (completed, declined, cancelled) blocks nothing.
 *
 * <p>An operation is covered by every request that covers its product: a
 * razrada decides which of the product's operations an order needs, and a
 * manufacturing time is asked for the product as a whole.
 */
@Component
@RequiredArgsConstructor
public class OpenRequestBlockers {

    private static final Set<ManufacturingTimeRequestStatus> OPEN_TIME_STATUSES =
            Set.of(ManufacturingTimeRequestStatus.PENDING, ManufacturingTimeRequestStatus.IN_REVIEW);

    private static final Set<ProductionOrderScopeRequestStatus> OPEN_SCOPE_STATUSES =
            Set.of(ProductionOrderScopeRequestStatus.PENDING, ProductionOrderScopeRequestStatus.IN_REVIEW);

    private final ManufacturingTimeRequestRepository timeRequestRepository;
    private final ProductionOrderScopeRequestRepository scopeRequestRepository;

    /** The open-request blockers for one product, as sentences a modal can print. */
    public List<String> forProduct(Long productId) {
        List<String> blockers = new ArrayList<>();

        for (ProductionOrderScopeRequest request :
                scopeRequestRepository.findOpenForProduct(productId, OPEN_SCOPE_STATUSES)) {
            blockers.add("Zahtev za razradu naloga %s %s".formatted(
                    request.getProductionOrder().getCode(),
                    statusWords(request.getStatus() == ProductionOrderScopeRequestStatus.IN_REVIEW)));
        }

        for (ManufacturingTimeRequest request :
                timeRequestRepository.findByProduct_IdAndStatusIn(productId, OPEN_TIME_STATUSES)) {
            blockers.add("Zahtev za vreme izrade (#%d) %s".formatted(
                    request.getId(),
                    statusWords(request.getStatus() == ManufacturingTimeRequestStatus.IN_REVIEW)));
        }

        return blockers;
    }

    private static String statusWords(boolean claimed) {
        return claimed ? "je preuzet — sačekajte da se obradi" : "čeka na obradu";
    }
}
