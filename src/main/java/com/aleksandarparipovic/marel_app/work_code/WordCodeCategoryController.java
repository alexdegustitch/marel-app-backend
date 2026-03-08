package com.aleksandarparipovic.marel_app.work_code;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/work-code-categories")
@RequiredArgsConstructor
public class WordCodeCategoryController {
    private final WorkCodeCategoryService service;

}
