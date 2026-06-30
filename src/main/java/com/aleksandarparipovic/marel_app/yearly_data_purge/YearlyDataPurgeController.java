package com.aleksandarparipovic.marel_app.yearly_data_purge;

import com.aleksandarparipovic.marel_app.yearly_data_purge.dto.YearlyDataPurgeResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/yearly-data-purge")
@RequiredArgsConstructor
public class YearlyDataPurgeController {

    private final YearlyDataPurgeService service;

    @GetMapping("/{year}/preview")
    public ResponseEntity<YearlyDataPurgeResultDto> preview(@PathVariable int year) {
        return ResponseEntity.ok(service.preview(year));
    }

    @PostMapping("/{year}")
    public ResponseEntity<YearlyDataPurgeResultDto> execute(@PathVariable int year) {
        return ResponseEntity.ok(service.execute(year));
    }
}

