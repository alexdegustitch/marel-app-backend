package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a payroll adjustment category's display name in a requested locale,
 * with fallback to the master name. Same contract and same batching discipline
 * as {@link com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryNameResolver}.
 *
 * <p>A payroll adjustment's name is ALWAYS resolved through its category. The
 * adjustment rows themselves store no name, translated or otherwise.
 */
@Component
@RequiredArgsConstructor
public class PayrollAdjustmentCategoryNameResolver {

    private final PayrollAdjustmentCategoryTranslationRepository translationRepository;

    /** category id -> translated name, for this locale's explicit rows only. */
    @Transactional(readOnly = true)
    public Map<Long, String> translationsFor(String requestedLocale) {
        String locale = AppLocales.normalize(requestedLocale);
        Map<Long, String> byCategoryId = new HashMap<>();
        translationRepository.findAllByLocale(locale).forEach(t ->
                byCategoryId.put(t.getPayrollAdjustmentCategory().getId(), t.getName()));
        return byCategoryId;
    }

    public String displayName(Long categoryId, String defaultName, Map<Long, String> translations) {
        if (translations == null || categoryId == null) {
            return defaultName;
        }
        String translated = translations.get(categoryId);
        return translated != null && !translated.isBlank() ? translated : defaultName;
    }
}
