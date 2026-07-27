package com.aleksandarparipovic.marel_app.work_code;


import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryTranslationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkCodeCategoryService {

    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final WorkCodeCategoryTranslationRepository translationRepository;
    private final WorkCodeCategoryNameResolver nameResolver;
    private final WorkCodeCategoryMapper mapper;

    /**
     * @param requestedLocale the locale for {@code displayName}; unknown or
     *                        missing falls back to the default locale.
     */
    @Transactional(readOnly = true)
    public List<WorkCodeCategoryDto> getAllWorkCodeCategories(String requestedLocale) {
        Map<Long, String> translations = nameResolver.translationsFor(requestedLocale);
        Map<Long, String> englishNames = AppLocales.ENGLISH.equalsIgnoreCase(AppLocales.normalize(requestedLocale))
                ? translations
                : englishNames();

        return workCodeCategoryRepository.findByArchivedAtIsNullOrderByDisplayOrderAscIdAsc()
                .stream()
                .map(category -> mapper.mapToDto(category, translations, englishNames))
                .toList();
    }

    /**
     * Set or clear a category's English name.
     *
     * <p>The Serbian name is NOT stored here — it lives on
     * {@code work_code_categories.category_name}, so there is one place to edit
     * it. A blank value removes the translation and the name falls back to the
     * master name, rather than storing an empty string that would render as a
     * gap on a payslip.
     */
    @Transactional
    public WorkCodeCategoryDto setEnglishName(Long categoryId, String nameEn) {
        WorkCodeCategory category = workCodeCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("Kategorija rada ne postoji: " + categoryId));

        var existing = translationRepository.findByCategoryAndLocale(categoryId, AppLocales.ENGLISH);

        if (nameEn == null || nameEn.isBlank()) {
            existing.ifPresent(translationRepository::delete);
        } else {
            WorkCodeCategoryTranslation translation = existing.orElseGet(() ->
                    WorkCodeCategoryTranslation.builder()
                            .workCodeCategory(category)
                            .locale(AppLocales.ENGLISH)
                            .build());
            translation.setName(nameEn.trim());
            translationRepository.save(translation);
        }

        Map<Long, String> englishNames = englishNames();
        return mapper.mapToDto(category, Map.of(), englishNames);
    }

    private Map<Long, String> englishNames() {
        Map<Long, String> byCategoryId = new HashMap<>();
        translationRepository.findAllByLocale(AppLocales.ENGLISH).forEach(t ->
                byCategoryId.put(t.getWorkCodeCategory().getId(), t.getName()));
        return byCategoryId;
    }
}
