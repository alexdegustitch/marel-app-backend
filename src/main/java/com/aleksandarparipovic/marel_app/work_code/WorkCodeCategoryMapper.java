package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import org.springframework.stereotype.Component;

@Component
public class WorkCodeCategoryMapper {

    WorkCodeCategoryDto mapToDto(WorkCodeCategory category){
        return new WorkCodeCategoryDto(
                category.getId(),
                category.getCategoryNo(),
                category.getCategoryName(),
                category.getNormMultiplier(),
                category.getNote(),
                category.getHourlyRate(),
                category.getFixedHourlyRate(),
                category.getAffectsMealAllowance(),
                category.getDisplayOrder(),
                category.getBaseCategory(),
                category.getAllowsParallelWork()
        );
    }

}
