package com.aleksandarparipovic.marel_app.compensation_scheme;

import com.aleksandarparipovic.marel_app.compensation_scheme.dto.CompensationSchemeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/compensation-schemes")
@RequiredArgsConstructor
public class CompensationSchemeController {

    private final CompensationSchemeRepository repository;

    /** The schemes an administrator may assign — active and not archived. */
    @GetMapping
    public ResponseEntity<List<CompensationSchemeDto>> listActive() {
        return ResponseEntity.ok(
                repository.findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc().stream()
                        .map(CompensationSchemeDto::from)
                        .toList());
    }
}
