package com.aleksandarparipovic.marel_app.sample_order;

import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCardRow;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderDetailDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemNoteDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderLineItemQuantityDto;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderOptionDto;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import com.aleksandarparipovic.marel_app.sample_order_line_item_note.SampleOrderLineItemNote;
import com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.SampleOrderLineItemQuantity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SampleOrderMapper {

    SampleOrderOptionDto toOptionDto(SampleOrder order) {
        return new SampleOrderOptionDto(order.getId(), order.getCode(), order.getName());
    }

    public SampleOrderLineItemQuantityDto toLineItemQuantityDto(SampleOrderLineItemQuantity quantity) {
        return new SampleOrderLineItemQuantityDto(
                quantity.getId(),
                quantity.getOrderQuantity(),
                quantity.getQuantity(),
                quantity.getIsActive(),
                quantity.getCreatedAt()
        );
    }

    public SampleOrderLineItemNoteDto toLineItemNoteDto(SampleOrderLineItemNote note) {
        return new SampleOrderLineItemNoteDto(
                note.getId(),
                // The column is called order_quantity on this table — a naming
                // slip in the original schema, mapped as-is by the entity and
                // named for what it is only here, where the client reads it.
                note.getOrderQuantity(),
                note.getNote(),
                note.getIsActive(),
                note.getCreatedAt()
        );
    }

    public SampleOrderLineItemDto toLineItemDto(
            SampleOrderLineItem lineItem,
            List<SampleOrderLineItemQuantityDto> quantities,
            List<SampleOrderLineItemNoteDto> notes
    ) {
        return new SampleOrderLineItemDto(
                lineItem.getId(),
                lineItem.getProduct().getId(),
                lineItem.getProduct().getProductName(),
                lineItem.getProduct().getProductCode(),
                lineItem.getProductDescription(),
                lineItem.getOrderLine(),
                lineItem.getQuantity(),
                lineItem.getNote(),
                quantities,
                notes
        );
    }

    public SampleOrderCardRow toCardRow(SampleOrder order, int lineItemCount, int totalQuantity) {
        return new SampleOrderCardRow(
                order.getId(),
                order.getCode(),
                order.getName(),
                order.getNote(),
                order.getStatus(),
                order.getCreationDate(),
                order.getDeadlineDate(),
                order.getDeadlineNote(),
                order.getCustomer() != null ? order.getCustomer().getId() : null,
                order.getCustomer() != null ? order.getCustomer().getName() : null,
                lineItemCount,
                totalQuantity
        );
    }

    public SampleOrderDetailDto toDetailDto(SampleOrder order, List<SampleOrderLineItemDto> lineItems) {
        return new SampleOrderDetailDto(
                order.getId(),
                order.getCode(),
                order.getName(),
                order.getNote(),
                order.getStatus(),
                order.getCreationDate(),
                order.getDeadlineDate(),
                order.getDeadlineNote(),
                order.getUser() != null ? order.getUser().getId() : null,
                order.getUser() != null ? order.getUser().getFullName() : null,
                order.getClosedBy() != null ? order.getClosedBy().getId() : null,
                order.getClosedBy() != null ? order.getClosedBy().getFullName() : null,
                order.getCustomer() != null ? order.getCustomer().getId() : null,
                order.getCustomer() != null ? order.getCustomer().getName() : null,
                lineItems
        );
    }
}
