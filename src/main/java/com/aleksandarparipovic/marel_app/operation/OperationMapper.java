package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationBasicInfoDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import org.springframework.stereotype.Component;

@Component
public class OperationMapper {

    public OperationDto toDto(Operation o) {
        OperationDto dto = new OperationDto();
        dto.setId(o.getId());
        dto.setProductId(o.getProduct() == null ? null : o.getProduct().getId());
        dto.setOperationName(o.getOpName());
        dto.setDescription(o.getDescription());
        dto.setMinNorm(o.getMinNorm());
        dto.setMaxNorm(o.getMaxNorm());
        dto.setNormRequired(o.isNormRequired());
        dto.setUnitsPerProduct(o.getUnitsPerProduct());
        dto.setNormDate(o.getNormDate());
        /*
         * A norm entered without a date ON PURPOSE. Carried here too, not only
         * on the grid's row: the product page now writes norms as well, and a
         * bare dash cannot say whether the date is missing or was never meant
         * to be there.
         */
        dto.setNormTemporary(o.isTemporary());
        dto.setWorkCodeCategoryId(o.getWorkCodeCategory() != null ? o.getWorkCodeCategory().getId() : null);

        dto.setSortOrder(o.getSortOrder());
        if (o.getOperationGroup() != null) {
            dto.setOperationGroupId(o.getOperationGroup().getId());
            dto.setOperationGroupName(o.getOperationGroup().getName());
        }
        dto.setGroupDefault(o.isGroupDefault());
        // One row by the service's rule (SPEC odluka 4); first() is the whole set.
        o.getVariantBindings().stream().findFirst().ifPresent(binding -> {
            dto.setBindingAttributeId(binding.getAttribute().getId());
            dto.setBindingAttributeName(binding.getAttribute().getName());
            dto.setBindingOptionId(binding.getOption().getId());
            dto.setBindingOptionValue(binding.getOption().getValue());
        });
        return dto;
    }

    public OperationBasicInfoDto toBasicDto(Operation o){
        Long workCodeCategoryId = o.getWorkCodeCategory() != null ? o.getWorkCodeCategory().getId() : null;
        OperationVariantOption binding = o.getVariantBindings().stream().findFirst().orElse(null);
        return new OperationBasicInfoDto(
                o.getId(), o.getOpName(), o.getMinNorm(), workCodeCategoryId,
                o.getOperationGroup() == null ? null : o.getOperationGroup().getId(),
                o.getOperationGroup() == null ? null : o.getOperationGroup().getName(),
                o.isGroupDefault(),
                binding == null ? null : binding.getAttribute().getName(),
                binding == null ? null : binding.getOption().getId(),
                binding == null ? null : binding.getOption().getValue());
    }
}
