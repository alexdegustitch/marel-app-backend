package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.work_log.dto.CreateUpdateWorkLogsRequest;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/work-logs")
@RequiredArgsConstructor
public class WorkLogController {

    private final WorkLogService workLogService;


    @PostMapping("/batch")
    public ResponseEntity<List<WorkLogDto>> updateInsertWorkLogs(@RequestBody CreateUpdateWorkLogsRequest request){
        return ResponseEntity.ok(workLogService.handleBatch(request));
    }

    @GetMapping()
    public ResponseEntity<List<WorkLogDto>> fetchAllActiveWorkLogsForShift(@RequestParam Long workShiftId){
        return ResponseEntity.ok(workLogService.fetchAllActiveLogsForShift(workShiftId));
    }

}
