package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a work-code category's display name in a requested locale, with
 * fallback to the master name.
 *
 * <p>The contract is {@code COALESCE(translation.name, category.category_name)}:
 * a missing translation yields the Serbian name, never null and never an empty
 * label on a payslip.
 *
 * <p><b>Load the table once, then resolve from memory.</b> The only query shape
 * offered is "every translation for this locale". Callers building a payslip or
 * a category list call {@link #translationsFor(String)} once and pass the map to
 * {@link #displayName}. Resolving inside a loop with a per-category query is the
 * mistake this class exists to prevent — a payslip has a dozen categories and
 * dozens of adjustments, and each would otherwise be a round-trip.
 */
@Component
@RequiredArgsConstructor
public class WorkCodeCategoryNameResolver {

    private final WorkCodeCategoryTranslationRepository translationRepository;

    /**
     * category id -> translated name, for the locale's explicit rows only.
     *
     * <p>The default locale is served entirely from the master column and is not
     * seeded, so this returns an empty map for it and every name falls back —
     * which is the intended behaviour, not a missing-data problem.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> translationsFor(String requestedLocale) {
        String locale = AppLocales.normalize(requestedLocale);
        Map<Long, String> byCategoryId = new HashMap<>();
        translationRepository.findAllByLocale(locale).forEach(t ->
                byCategoryId.put(t.getWorkCodeCategory().getId(), t.getName()));
        return byCategoryId;
    }

    /** The translated name if one exists, otherwise the master name. */
    public String displayName(WorkCodeCategory category, Map<Long, String> translations) {
        if (category == null) {
            return null;
        }
        return displayName(category.getId(), category.getCategoryName(), translations);
    }

    /** Id/name overload for projection-based read paths that never load the entity. */
    public String displayName(Long categoryId, String defaultName, Map<Long, String> translations) {
        if (translations == null || categoryId == null) {
            return defaultName;
        }
        String translated = translations.get(categoryId);
        return translated != null && !translated.isBlank() ? translated : defaultName;
    }

    /** Single-category convenience for one-off callers. Not for use in loops. */
    @Transactional(readOnly = true)
    public String displayName(WorkCodeCategory category, String requestedLocale) {
        if (category == null) {
            return null;
        }
        String locale = AppLocales.normalize(requestedLocale);
        return translationRepository.findByCategoryAndLocale(category.getId(), locale)
                .map(WorkCodeCategoryTranslation::getName)
                .filter(name -> !name.isBlank())
                .orElseGet(category::getCategoryName);
    }
}
