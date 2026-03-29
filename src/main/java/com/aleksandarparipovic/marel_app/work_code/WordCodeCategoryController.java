package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import jakarta.persistence.Cacheable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/work-code-categories")
@RequiredArgsConstructor
public class WordCodeCategoryController {
    private final WorkCodeCategoryService service;

    @GetMapping("/active-work-code-categories")
    public ResponseEntity<List<WorkCodeCategoryDto>> getAllActiveWorkCategories(){
        List<WorkCodeCategoryDto> response = service.getAllWorkCodeCategories();
        return ResponseEntity.ok(response);
    }
}
