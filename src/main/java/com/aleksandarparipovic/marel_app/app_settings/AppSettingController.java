package com.aleksandarparipovic.marel_app.app_settings;

import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingHistoryDto;
import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingResponse;
import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/app-settings")
@RequiredArgsConstructor
public class AppSettingController {

    private final AppSettingService appSettingService;

    @GetMapping
    public ResponseEntity<List<AppSettingResponse>> getAllCurrentlyValid() {
        return ResponseEntity.ok(appSettingService.getAllCurrentlyValid());
    }

    @PostMapping
    public ResponseEntity<AppSettingResponse> saveSetting(
            @Valid @RequestBody AppSettingUpdateRequest request) {
        return ResponseEntity.ok(appSettingService.saveSetting(request));
    }

    @GetMapping("/history")
    public ResponseEntity<List<AppSettingHistoryDto>> getAllHistory() {
        return ResponseEntity.ok(appSettingService.getAllHistory());
    }

    /**
     * GET /api/app-settings/value?key=meal_allowance_per_day&at=2026-05-01T00:00:00Z
     * Returns the numeric value of the setting that was valid at the given time.
     * Returns 404 if no matching setting exists.
     */
    @GetMapping("/value")
    public ResponseEntity<BigDecimal> getSettingAt(
            @RequestParam String key,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        BigDecimal value = appSettingService.getSettingAt(key, at);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }
}

