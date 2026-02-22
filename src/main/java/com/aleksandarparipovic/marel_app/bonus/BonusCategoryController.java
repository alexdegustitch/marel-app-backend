package com.aleksandarparipovic.marel_app.bonus;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;
import com.aleksandarparipovic.marel_app.bonus.dto.*;

@RestController
@RequestMapping("/api/bonus-categories")
@RequiredArgsConstructor
public class BonusCategoryController {

    private final BonusCategoryService bonusCategoryService;

    @GetMapping
    public ResponseEntity<List<BonusCategoryDto>> search(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) LocalDate validOn
    ) {
        return ResponseEntity.ok(bonusCategoryService.search(active, code, validOn));
    }

    @GetMapping("/active-bonuses")
    @Cacheable("active-bonuses")
    public ResponseEntity<List<BonusCategoryOptionDto>> getAllActiveBonusCategories(){
        List<BonusCategoryOptionDto> result = bonusCategoryService.getAllActiveBonusCategories();
        return ResponseEntity.ok(result);
    }
}
