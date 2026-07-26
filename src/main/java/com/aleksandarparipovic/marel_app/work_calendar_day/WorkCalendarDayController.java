package com.aleksandarparipovic.marel_app.work_calendar_day;

import com.aleksandarparipovic.marel_app.work_calendar_day.dto.UpdateWorkCalendarDayRequest;
import com.aleksandarparipovic.marel_app.work_calendar_day.dto.WorkCalendarDayDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/work-calendar")
@RequiredArgsConstructor
public class WorkCalendarDayController {

    private final WorkCalendarDayService workCalendarDayService;

    @GetMapping
    ResponseEntity<List<WorkCalendarDayDto>> getYear(@RequestParam int year) {
        return ResponseEntity.ok(workCalendarDayService.getYear(year));
    }

    @PostMapping("/auto-fill")
    ResponseEntity<List<WorkCalendarDayDto>> autoFillYear(@RequestParam int year) {
        return ResponseEntity.ok(workCalendarDayService.autoFillYear(year));
    }

    @PutMapping("/range")
    ResponseEntity<List<WorkCalendarDayDto>> updateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Valid @RequestBody UpdateWorkCalendarDayRequest request
    ) {
        return ResponseEntity.ok(workCalendarDayService.updateRange(from, to, request));
    }

    @PutMapping("/{date}")
    ResponseEntity<WorkCalendarDayDto> updateDay(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody UpdateWorkCalendarDayRequest request
    ) {
        return ResponseEntity.ok(workCalendarDayService.updateDay(date, request));
    }
}
