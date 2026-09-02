package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceCategoryDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceCreateRequest;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceRecordDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.MonthlyAbsencesDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeBankDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.SuggestedAbsenceDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/absences")
@RequiredArgsConstructor
public class AbsenceRecordController {

    private final AbsenceRecordService service;

    @GetMapping("/shift/{workShiftId}")
    public ResponseEntity<List<AbsenceRecordDto>> forShift(@PathVariable Long workShiftId) {
        return ResponseEntity.ok(service.forShift(workShiftId));
    }

    /** The karton's view: a month of absences with the bank that decided them. */
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<MonthlyAbsencesDto> forMonth(@PathVariable Long employeeId,
                                                       @RequestParam int year,
                                                       @RequestParam int month) {
        return ResponseEntity.ok(service.forMonth(employeeId, year, month));
    }

    /** The stretches of the shift nothing is recorded for — offered, not applied. */
    @GetMapping("/shift/{workShiftId}/suggestions")
    public ResponseEntity<List<SuggestedAbsenceDto>> suggestions(@PathVariable Long workShiftId) {
        return ResponseEntity.ok(service.suggestionsForShift(workShiftId));
    }

    /** What kinds of absence may be chosen on that date. Never includes ND. */
    @GetMapping("/categories")
    public ResponseEntity<List<AbsenceCategoryDto>> categories(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate) {
        return ResponseEntity.ok(service.selectableCategories(workDate));
    }

    /** The bank of the month this shift falls in. Employee and month come from it. */
    @GetMapping("/shift/{workShiftId}/overtime-bank")
    public ResponseEntity<OvertimeBankDto> bankForShift(@PathVariable Long workShiftId) {
        return ResponseEntity.ok(service.bankForShift(workShiftId));
    }

    @PostMapping
    public ResponseEntity<AbsenceRecordDto> create(@Valid @RequestBody AbsenceCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    /** Withdraws an absence. Archived, not deleted — see the service. */
    /** Ask for this day to be bought back; the allocation answers. */
    @PostMapping("/{id}/request-non-working-day")
    public ResponseEntity<AbsenceRecordDto> requestNonWorkingDay(@PathVariable Long id) {
        return ResponseEntity.ok(service.requestNonWorkingDay(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }
}
