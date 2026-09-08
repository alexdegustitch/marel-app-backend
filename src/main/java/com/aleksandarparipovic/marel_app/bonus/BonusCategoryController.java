package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryDto;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryOptionDto;
import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Bonus categories. The whole path is behind BONUS_RULE_MANAGE (SecurityConfig),
 * which the supervisor holds — bonuses are reference data the supervisor
 * maintains by hand: a code, an amount, and the dates it is valid for.
 *
 * <p>Every write evicts the "active-bonuses" cache, or the pickers built on it
 * would keep offering yesterday's list.
 */
@RestController
@RequestMapping("/api/bonus-categories")
@RequiredArgsConstructor
public class BonusCategoryController {

    private final BonusCategoryService bonusCategoryService;
    private final BonusCategoryMapper mapper;

    /**
     * {@code includeArchived} is for the admin catalogue screen, which shows
     * archived categories so they can be restored; everybody else keeps the old
     * answer where an archived category simply does not exist.
     */
    @GetMapping
    public ResponseEntity<List<BonusCategoryDto>> search(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) LocalDate validOn,
            @RequestParam(required = false, defaultValue = "false") boolean includeArchived
    ) {
        return ResponseEntity.ok(bonusCategoryService.search(active, code, validOn, includeArchived));
    }

    @GetMapping("/active-bonuses")
    @Cacheable("active-bonuses")
    public ResponseEntity<List<BonusCategoryOptionDto>> getAllActiveBonusCategories(){
        List<BonusCategoryOptionDto> result = bonusCategoryService.getAllActiveBonusCategories();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/active-valid")
    public ResponseEntity<List<BonusCategoryOptionDto>> getActiveAndValidBonusCategories() {
        return ResponseEntity.ok(bonusCategoryService.getActiveAndValidBonusCategories());
    }

    @PostMapping
    @CacheEvict(value = "active-bonuses", allEntries = true)
    public ResponseEntity<BonusCategoryDto> create(@Valid @RequestBody BonusCategoryCreateRequest request) {
        return ResponseEntity.ok(mapper.toDto(bonusCategoryService.create(toEntity(request))));
    }

    @PatchMapping("/{id}")
    @CacheEvict(value = "active-bonuses", allEntries = true)
    public ResponseEntity<BonusCategoryDto> update(
            @PathVariable Long id,
            @Valid @RequestBody BonusCategoryCreateRequest request
    ) {
        return ResponseEntity.ok(mapper.toDto(bonusCategoryService.update(id, toEntity(request))));
    }

    /**
     * Archive, signed with the caller's password — not a delete, payroll history
     * keeps referencing the category and restore undoes it.
     */
    @PostMapping("/{id}/archive")
    @CacheEvict(value = "active-bonuses", allEntries = true)
    public ResponseEntity<Void> archive(
            @PathVariable Long id,
            @Valid @RequestBody ArchiveConfirmationRequest request,
            Authentication authentication
    ) {
        bonusCategoryService.archive(id, request.getPassword(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    @CacheEvict(value = "active-bonuses", allEntries = true)
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        bonusCategoryService.restore(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The form's fields onto a fresh entity. {@code minHours} stays null (legacy,
     * not offered) and {@code active} is set here because the entity's field
     * initialiser does not survive the Lombok builder.
     */
    private static BonusCategory toEntity(BonusCategoryCreateRequest request) {
        String description = request.getDescription();
        return BonusCategory.builder()
                .categoryNo(request.getCategoryNo().trim())
                .categoryName(request.getCategoryName().trim())
                .bonusAmount(request.getBonusAmount())
                .validFrom(request.getValidFrom())
                .validUntil(request.getValidUntil())
                .description(description == null || description.isBlank() ? null : description.trim())
                .active(true)
                .build();
    }
}
