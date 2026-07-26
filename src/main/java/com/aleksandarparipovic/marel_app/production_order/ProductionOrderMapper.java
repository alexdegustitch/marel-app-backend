package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDeadlineDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemNoteDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderLineItemQuantityDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderOptionDto;
import com.aleksandarparipovic.marel_app.production_order_deadline.ProductionOrderDeadline;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class ProductionOrderMapper {

    ProductionOrderOptionDto toOptionDto(ProductionOrder productionOrder){
        return new ProductionOrderOptionDto(productionOrder.getId(), productionOrder.getCode(), productionOrder.getName());
    }

    public ProductionOrderDeadlineDto toDeadlineDto(ProductionOrderDeadline deadline) {
        return new ProductionOrderDeadlineDto(
                deadline.getId(),
                deadline.getDeadlineOrder(),
                deadline.getDeadlineDateFrom(),
                deadline.getDeadlineDateTo(),
                deadline.getQuantity(),
                deadline.getIsActive()
        );
    }

    public ProductionOrderLineItemQuantityDto toLineItemQuantityDto(ProductionOrderLineItemQuantity quantity) {
        return new ProductionOrderLineItemQuantityDto(
                quantity.getId(),
                quantity.getOrderQuantity(),
                quantity.getQuantity(),
                quantity.getDeliveryDeadline(),
                quantity.getIsActive()
        );
    }

    public ProductionOrderLineItemNoteDto toLineItemNoteDto(ProductionOrderLineItemNote note) {
        return new ProductionOrderLineItemNoteDto(
                note.getId(),
                note.getOrderNote(),
                note.getNote(),
                note.getIsActive(),
                note.getCreatedAt()
        );
    }

    public ProductionOrderLineItemDto toLineItemDto(
            ProductionOrderLineItem lineItem,
            List<ProductionOrderLineItemQuantityDto> quantities,
            List<ProductionOrderLineItemNoteDto> notes
    ) {
        return new ProductionOrderLineItemDto(
                lineItem.getId(),
                lineItem.getProduct().getId(),
                lineItem.getProduct().getProductName(),
                lineItem.getProductDescription(),
                lineItem.getLineOrder(),
                lineItem.getNote(),
                quantities,
                notes
        );
    }

    public ProductionOrderCardRow toCardRow(
            ProductionOrder order,
            List<ProductionOrderDeadlineDto> deadlines,
            LocalDate effectiveDeadlineDate,
            Boolean effectiveDeadlineFromLineItem
    ) {
        return new ProductionOrderCardRow(
                order.getId(),
                order.getCode(),
                order.getName(),
                order.getNote(),
                order.getTestingRequired(),
                order.getStatus(),
                order.getDeliveryDeadline(),
                order.getIsHighPriority(),
                order.getIsAnnounced(),
                order.getHasSuccessiveDeliveries(),
                effectiveDeadlineDate,
                effectiveDeadlineFromLineItem,
                deadlines
        );
    }

    public ProductionOrderDetailDto toDetailDto(
            ProductionOrder order,
            List<ProductionOrderDeadlineDto> deadlines,
            List<ProductionOrderLineItemDto> lineItems
    ) {
        return new ProductionOrderDetailDto(
                order.getId(),
                order.getCode(),
                order.getName(),
                order.getNote(),
                order.getTestingRequired(),
                order.getStatus(),
                order.getCreationDate(),
                order.getOrderDate(),
                order.getDeliveryDeadline(),
                order.getIsHighPriority(),
                order.getIsAnnounced(),
                order.getHasSuccessiveDeliveries(),
                order.getUser() != null ? order.getUser().getFullName() : null,
                deadlines,
                lineItems
        );
    }
}
