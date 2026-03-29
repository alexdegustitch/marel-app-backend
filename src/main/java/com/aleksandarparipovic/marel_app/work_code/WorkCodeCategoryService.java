package com.aleksandarparipovic.marel_app.work_code;


import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryDto;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkCodeCategoryService {

    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final WorkCodeCategoryMapper mapper;

    public List<WorkCodeCategoryDto> getAllWorkCodeCategories(){
        return workCodeCategoryRepository.findByArchivedAtIsNullOrderByCategoryNo()
                .stream()
                .map(mapper::mapToDto)
                .toList();
    }

}
