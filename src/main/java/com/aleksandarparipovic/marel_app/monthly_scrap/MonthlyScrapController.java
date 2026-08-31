package com.aleksandarparipovic.marel_app.monthly_scrap;

import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapResponse;
import com.aleksandarparipovic.marel_app.monthly_scrap.dto.MonthlyScrapSaveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Monthly scrap, entered from the monthly records screen.
 *
 * <p>Guarded in {@code SecurityConfig}, inside the work-records block, by
 * {@code WORK_RECORD_VIEW} — the same permission that opens the screen this
 * lives on. That is where the area rules live, and it matches how work records
 * already work: the area permission carries the ordinary writes, and only the
 * exceptional acts (archiving a shift) get their own {@code @PreAuthorize}. A
 * permission of its own would have to be granted to exactly the roles that
 * already hold this one.
 *
 * <p>The month is in the PATH, not in a body. It is the month the user is
 * looking at, so a row cannot be filed under a different one.
 */
@RestController
@RequestMapping("/api/monthly-scraps")
@RequiredArgsConstructor
public class MonthlyScrapController {

    private final MonthlyScrapService monthlyScrapService;

    @GetMapping("/{year}/{month}")
    public ResponseEntity<List<MonthlyScrapResponse>> findForMonth(@PathVariable int year,
                                                                   @PathVariable int month) {
        return ResponseEntity.ok(monthlyScrapService.findForMonth(year, month));
    }

    @PostMapping("/{year}/{month}")
    public ResponseEntity<MonthlyScrapResponse> create(@PathVariable int year,
                                                       @PathVariable int month,
                                                       @Valid @RequestBody MonthlyScrapSaveRequest request) {
        return ResponseEntity.ok(monthlyScrapService.create(year, month, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MonthlyScrapResponse> update(@PathVariable Long id,
                                                       @Valid @RequestBody MonthlyScrapSaveRequest request) {
        return ResponseEntity.ok(monthlyScrapService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        monthlyScrapService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
