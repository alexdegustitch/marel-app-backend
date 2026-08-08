package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WorkCodeCategoryMapper {

    /**
     * @param translations category id -> name in the requested locale
     * @param englishNames category id -> English name, for administration
     *
     * <p>Both maps are loaded once by the caller and passed in. Resolving a
     * translation per category here would turn a category list into one query
     * per row.
     */
    WorkCodeCategoryDto mapToDto(WorkCodeCategory category,
                                 Map<Long, String> translations,
                                 Map<Long, String> englishNames) {
        String defaultName = category.getCategoryName();
        String translated = translations == null ? null : translations.get(category.getId());
        return new WorkCodeCategoryDto(
                category.getId(),
                category.getCategoryNo(),
                defaultName,
                translated != null && !translated.isBlank() ? translated : defaultName,
                englishNames == null ? null : englishNames.get(category.getId()),
                category.getNormMultiplier(),
                category.getNote(),
                category.getHourlyRate(),
                category.getFixedHourlyRate(),
                category.getAffectsMealAllowance(),
                category.getDisplayOrder(),
                category.getBaseCategory(),
                category.getBaseOperation(),
                category.getAllowsParallelWork()
        );
    }
}
