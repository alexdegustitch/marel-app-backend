package com.aleksandarparipovic.marel_app.assistant;

import com.aleksandarparipovic.marel_app.assistant.dto.AssistantTextResponse;
import com.aleksandarparipovic.marel_app.assistant.dto.ChatRequest;
import com.aleksandarparipovic.marel_app.assistant.dto.ChatResponse;
import com.aleksandarparipovic.marel_app.assistant.dto.EmployeeAnalysisRequest;
import com.aleksandarparipovic.marel_app.assistant.dto.EmployeeMonthReport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The "Spiky" AI assistant. Read-only: it returns a short Serbian analysis of
 * one employee's month and writes nothing.
 *
 * <p>Sits behind the same auth as every other {@code /api/**} endpoint (it falls
 * to {@code anyRequest().authenticated()} — no security config is added).
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final SparkyService sparkyService;
    private final AssistantProperties assistantProperties;

    /**
     * Spiky's summary is built DETERMINISTICALLY on the server from real figures
     * (no model call, nothing leaves the box), so it needs no API key and is
     * always available to a signed-in user.
     */
    @PostMapping("/employee-analysis")
    public ResponseEntity<AssistantTextResponse> employeeAnalysis(@Valid @RequestBody EmployeeAnalysisRequest request) {
        return ResponseEntity.ok(assistantService.analyze(request));
    }

    /** The karton's own summary, by monthly-record id. */
    @PostMapping("/record-analysis")
    public ResponseEntity<AssistantTextResponse> recordAnalysis(@Valid @RequestBody RecordAnalysisRequest request) {
        return ResponseEntity.ok(assistantService.analyzeByRecordId(request.employeeRecordId()));
    }

    /**
     * The karton's STRUCTURED monthly report, by monthly-record id. Built
     * deterministically from real DB figures (no model call); always 200, with
     * {@code hasData=false} when the record/report is missing.
     */
    @PostMapping("/employee-report")
    public ResponseEntity<EmployeeMonthReport> employeeReport(@Valid @RequestBody RecordAnalysisRequest request) {
        return ResponseEntity.ok(assistantService.buildEmployeeReport(request.employeeRecordId()));
    }

    /**
     * Sparky — the natural-language analytics assistant. Unlike the deterministic
     * endpoints above, it NEEDS the model (and therefore the API key): with no key
     * it answers 503 {@code {"error":"disabled"}} and never touches the quota.
     *
     * <p>A reached-the-model turn costs one quota unit (handled in the service);
     * quota and upstream failures are mapped by the {@code @ExceptionHandler}s below.
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest request) {
        if (!assistantProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "disabled"));
        }
        return ResponseEntity.ok(new ChatResponse(sparkyService.chat(request)));
    }

    /** Body for {@link #recordAnalysis} and {@link #employeeReport}. */
    public record RecordAnalysisRequest(@jakarta.validation.constraints.NotNull Long employeeRecordId) {
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<?> handleQuota(QuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "error", "quota",
                        "message", "Dostigli ste dnevni limit pitanja (" + ex.getLimit()
                                + "). Pokušajte ponovo sutra."));
    }

    @ExceptionHandler(AssistantUpstreamException.class)
    public ResponseEntity<?> handleUpstream(AssistantUpstreamException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "upstream", "message", "AI trenutno nije dostupan."));
    }
}
