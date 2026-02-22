package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import org.springframework.stereotype.Component;

@Component
public class OperationMapper {

    public OperationDto toDto(Operation o) {
        OperationDto dto = new OperationDto();
        dto.setId(o.getId());
        dto.setProductId(o.getProduct().getId());
        dto.setOperationName(o.getOpName());
        dto.setMinNorm(o.getMinNorm());
        dto.setMaxNorm(o.getMaxNorm());
        dto.setUnitsPerProduct(o.getUnitsPerProduct());
        dto.setNormDate(o.getNormDate());
        return dto;
    }
}
