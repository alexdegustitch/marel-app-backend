package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.work_code.dto.UpdateWorkCodeCategoryTranslationRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/work-code-categories")
@RequiredArgsConstructor
public class WordCodeCategoryController {
    private final WorkCodeCategoryService service;

    /**
     * @param locale optional. Selects {@code displayName}; the untranslated
     *               {@code name} and the stable {@code no} are always present, so
     *               an existing client that ignores the parameter is unaffected.
     */
    @GetMapping("/active-work-code-categories")
    public ResponseEntity<List<WorkCodeCategoryDto>> getAllActiveWorkCategories(
            @RequestParam(required = false) String locale,
            @RequestParam(required = false, defaultValue = "false") boolean baseOperationsOnly
    ) {
        return ResponseEntity.ok(service.getAllWorkCodeCategories(locale, baseOperationsOnly));
    }

    /**
     * Set or clear a category's English name.
     *
     * <p>Scoped to the translation deliberately. Work-code categories have no
     * general CRUD endpoint in this application, and adding one to hang a
     * translation field off would be a much larger change than this feature
     * needs.
     */
    @PutMapping("/{id}/translations/en")
    public ResponseEntity<WorkCodeCategoryDto> setEnglishName(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkCodeCategoryTranslationRequest request
    ) {
        return ResponseEntity.ok(service.setEnglishName(id, request.getName()));
    }
}
