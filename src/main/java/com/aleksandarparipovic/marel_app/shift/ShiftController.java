package com.aleksandarparipovic.marel_app.shift;
import com.aleksandarparipovic.marel_app.shift.dto.ShiftOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;
    @GetMapping("/active-shifts")
    public ResponseEntity<List<ShiftOptionDto>> getActiveShiftOptions() {
        return ResponseEntity.ok(shiftService.getActiveShiftOptions());
    }
}
