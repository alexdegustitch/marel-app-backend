package com.aleksandarparipovic.marel_app.work_code.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * The English name for one work-code category.
 *
 * <p>Not {@code @NotBlank}: a blank or null value is the documented way to
 * REMOVE a translation, after which the name falls back to the master
 * {@code category_name}.
 */
@Getter
@Setter
public class UpdateWorkCodeCategoryTranslationRequest {
    private String name;
}
