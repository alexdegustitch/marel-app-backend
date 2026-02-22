package com.aleksandarparipovic.marel_app.product.dto;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductWithOperationListRow {
    ProductWithOperationCountRow productInfo;
    List<OperationDto> operationList;
}
