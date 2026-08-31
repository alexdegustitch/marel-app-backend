package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceCreateRequest;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceRecordDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeBankDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.SuggestedAbsenceDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /** The stretches of the shift nothing is recorded for — offered, not applied. */
    @GetMapping("/shift/{workShiftId}/suggestions")
    public ResponseEntity<List<SuggestedAbsenceDto>> suggestions(@PathVariable Long workShiftId) {
        return ResponseEntity.ok(service.suggestionsForShift(workShiftId));
    }

    @GetMapping("/overtime-bank")
    public ResponseEntity<OvertimeBankDto> bank(@RequestParam Long employeeId,
                                                @RequestParam int year,
                                                @RequestParam int month) {
        return ResponseEntity.ok(service.bankFor(employeeId, year, month));
    }

    @PostMapping
    public ResponseEntity<AbsenceRecordDto> create(@Valid @RequestBody AbsenceCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    /** Withdraws an absence. Archived, not deleted — see the service. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }
}
