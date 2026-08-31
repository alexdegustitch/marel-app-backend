package com.aleksandarparipovic.marel_app.monthly_scrap.dto;

import com.aleksandarparipovic.marel_app.monthly_scrap.MonthlyScrap;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * One scrap row, with the names the list shows beside the ids the editor needs.
 *
 * <p>The labels are resolved here rather than looked up again per row on the
 * client: the row is already loaded with its associations, and a list that has
 * to match ids against three option lists shows blanks whenever one of them is
 * still loading.
 */
@Getter
public class MonthlyScrapResponse {

    private final Long id;
    private final LocalDate period;

    private final Long productId;
    private final String productName;
    private final String productCode;

    private final Long operationId;
    private final String operationName;

    private final Long productionOrderId;
    private final String productionOrderCode;
    private final String productionOrderName;

    private final Integer quantity;
    private final String note;

    public MonthlyScrapResponse(MonthlyScrap scrap) {
        this.id = scrap.getId();
        this.period = scrap.getPeriod();

        this.productId = scrap.getProduct() == null ? null : scrap.getProduct().getId();
        this.productName = scrap.getProduct() == null ? null : scrap.getProduct().getProductName();
        this.productCode = scrap.getProduct() == null ? null : scrap.getProduct().getProductCode();

        this.operationId = scrap.getOperation() == null ? null : scrap.getOperation().getId();
        this.operationName = scrap.getOperation() == null ? null : scrap.getOperation().getOpName();

        ProductionOrder order = scrap.getProductionOrder();
        this.productionOrderId = order == null ? null : order.getId();
        this.productionOrderCode = order == null ? null : order.getCode();
        this.productionOrderName = order == null ? null : order.getName();

        this.quantity = scrap.getQuantity();
        this.note = scrap.getNote();
    }
}
