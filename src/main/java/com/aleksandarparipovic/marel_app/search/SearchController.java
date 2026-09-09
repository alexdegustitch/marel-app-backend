package com.aleksandarparipovic.marel_app.search;

import com.aleksandarparipovic.marel_app.search.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified, read-only global search for the frontend command palette.
 *
 * <p>No matcher of its own in {@code SecurityConfig}, so it falls to
 * {@code anyRequest().authenticated()} — the same gate as the catalogue
 * controllers it draws from. It returns only names, codes and deep links; no
 * payroll figure passes through it.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * @param q     residual search text (name, code or number); may be blank
     * @param month 1-12, optional; with {@code year} it unlocks karton results
     * @param year  e.g. 2026, optional
     * @param types CSV of types to restrict to; blank means all types
     * @param limit max results PER TYPE (default 8)
     */
    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String types,
            @RequestParam(required = false, defaultValue = "8") int limit
    ) {
        return ResponseEntity.ok(new SearchResponse(
                searchService.search(q, month, year, types, limit)));
    }
}
