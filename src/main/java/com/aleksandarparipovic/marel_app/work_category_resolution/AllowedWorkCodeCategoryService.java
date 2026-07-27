package com.aleksandarparipovic.marel_app.work_category_resolution;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.work_category_resolution.dto.AllowedWorkCodeCategoryDto;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryNameResolver;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Presentation layer over {@link WorkCategoryResolutionService}: the same
 * resolution results, localised and shaped for the work-entry form.
 *
 * <p>Contains no availability or coefficient logic of its own. Everything that
 * decides what an employee may select lives in the resolver; duplicating any of
 * it here is exactly the drift this split is meant to prevent.
 */
@Service
@RequiredArgsConstructor
public class AllowedWorkCodeCategoryService {

    private final WorkCategoryResolutionService resolutionService;
    private final WorkCodeCategoryNameResolver nameResolver;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;

    @Transactional(readOnly = true)
    public List<AllowedWorkCodeCategoryDto> listFor(Long employeeId, LocalDate workDate, String requestedLocale) {
        String locale = AppLocales.normalize(requestedLocale);
        Map<Long, String> translations = nameResolver.translationsFor(locale);

        List<WorkCategoryResolution> resolutions =
                resolutionService.listAllowedCategories(employeeId, workDate);

        // The effective category may be one no employee ever selects
        // (FOREIGN_ALL_SHIFTS), so it will not appear among the resolutions. Load
        // the categories once and index them rather than querying per row.
        Map<Long, WorkCodeCategory> categoriesById = workCodeCategoryRepository.findAllById(
                        resolutions.stream()
                                .flatMap(r -> java.util.stream.Stream.of(r.sourceCategoryId(), r.effectiveCategoryId()))
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(WorkCodeCategory::getId, Function.identity()));

        return resolutions.stream()
                .map(r -> new AllowedWorkCodeCategoryDto(
                        r.sourceCategoryId(),
                        r.sourceCategoryCode(),
                        localizedName(r.sourceCategoryId(), categoriesById, translations),
                        r.effectiveCategoryId(),
                        r.effectiveCategoryCode(),
                        localizedName(r.effectiveCategoryId(), categoriesById, translations),
                        r.coefficient(),
                        r.coefficientOverridden(),
                        r.compensationSchemeCode()))
                .toList();
    }

    private String localizedName(Long categoryId,
                                 Map<Long, WorkCodeCategory> categoriesById,
                                 Map<Long, String> translations) {
        if (categoryId == null) {
            return null;
        }
        WorkCodeCategory category = categoriesById.get(categoryId);
        return category == null ? null : nameResolver.displayName(category, translations);
    }
}
