package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.absence_record.ShiftAbsenceSync;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.DailyRecalcRequestedEvent;
import com.aleksandarparipovic.marel_app.work_log.dto.CreateUpdateWorkLogsRequest;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogFormDto;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolutionService;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkLogService {

    private final WorkLogRepository repository;
    private final WorkLogMapper workLogMapper;
    private final RecalcQueueService recalcQueueService;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkShiftService workShiftService;
    private final WorkShiftRepository workShiftRepository;
    private final WorkCategoryResolutionService resolutionService;
    private final ShiftAbsenceSync shiftAbsenceSync;

    public List<WorkLogDto> fetchAllActiveLogsForShift(Long shiftId) {
        return repository.getAllActiveLogsForShift(shiftId);
    }

    @Transactional
    public List<WorkLogDto> handleBatch(CreateUpdateWorkLogsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        List<WorkLog> result = new ArrayList<>();

        // One resolution context per (employee, work date), reused across every
        // log in the batch. Resolving per log would issue two queries each, and a
        // shift's logs almost always share the same employee and date.
        ResolutionContextCache contexts = new ResolutionContextCache();

        if (request.getCreate() != null) {
            List<WorkLog> toCreate = request.getCreate()
                    .stream()
                    .map(dto -> workLogMapper.toEntity(dto, resolveForNewLog(contexts, dto)))
                    .toList();
            result.addAll(repository.saveAll(toCreate));
        }

        if (request.getUpdate() != null && !request.getUpdate().isEmpty()) {
            List<Long> ids = request.getUpdate().stream()
                    .map(WorkLogFormDto::getId)
                    .peek(id -> {
                        if (id == null) throw new IllegalArgumentException("Work log id is required for update");
                    })
                    .toList();

            Map<Long, WorkLog> existingMap = repository.findAllById(ids).stream()
                    .collect(Collectors.toMap(WorkLog::getId, Function.identity()));

            List<WorkLog> toUpdate = new ArrayList<>();
            for (WorkLogFormDto dto : request.getUpdate()) {
                WorkLog existing = existingMap.get(dto.getId());
                if (existing == null) throw new EntityNotFoundException("Work log not found: " + dto.getId());
                workLogMapper.updateEntity(existing, dto, resolveForExistingLog(contexts, existing, dto));
                toUpdate.add(existing);
            }
            result.addAll(toUpdate);
        }

        if (request.getDeleted() != null && !request.getDeleted().isEmpty()) {
            List<Long> ids = request.getDeleted().stream()
                    .map(WorkLogFormDto::getId)
                    .peek(id -> {
                        if (id == null) throw new IllegalArgumentException("Work log id is required for delete");
                    })
                    .toList();

            Map<Long, WorkLog> existingMap = repository.findAllById(ids).stream()
                    .collect(Collectors.toMap(WorkLog::getId, Function.identity()));

            List<WorkLog> toDelete = new ArrayList<>();
            for (WorkLogFormDto dto : request.getDeleted()) {
                WorkLog existing = existingMap.get(dto.getId());
                if (existing == null) throw new EntityNotFoundException("Work log not found: " + dto.getId());
                existing.setIsActive(false);
                toDelete.add(existing);
            }
            result.addAll(toDelete);
        }

        // ── Event-driven trigger: Enqueue daily recalculation jobs ───────────────────────
        // Within same transaction, mark daily jobs as PENDING.
        // After transaction commits, trigger workers to process immediately.
        Set<Long> processedShiftIds = new HashSet<>();
        for (WorkLog wl : result) {
            WorkShift shift = wl.getWorkShift();
            if (shift == null) continue;
            if (processedShiftIds.add(shift.getId())) {
                /*
                 * A WHOLE SHIFT NOBODY CAME IN IS TWO WRITES, NOT ONE.
                 *
                 * The NO log is how the day is drawn on the karton; the absence
                 * record is what the overtime bank and the weekend bonus are
                 * decided from. Entering one without the other would leave the
                 * feature silently doing nothing for that employee — no error, no
                 * message, just a day that can never become a neradni dan.
                 *
                 * Refuses the batch when the two could not agree: a NO log over
                 * part of a shift, or one standing beside recorded work. Both are
                 * conflicts rather than corrections, and the message says where
                 * such an absence goes instead.
                 */
                shiftAbsenceSync.syncForShift(shift);

                workShiftService.recalculateShiftBoundaries(shift);
                recalcQueueService.enqueueDailyJob(shift, "WORK_LOG_MUTATION");
            }
        }

        List<WorkLogDto> dtoResults = result.stream().map(workLogMapper::toDto).toList();

        eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.DAILY));

        return dtoResults;
    }

    /**
     * Validate and resolve the category for a log being created.
     *
     * <p>The category is revalidated here regardless of what the client sent.
     * Having appeared in a dropdown earlier is not evidence that a category is
     * still valid for the employee and date now being submitted, and nothing
     * stops a client from posting an id it never saw.
     */
    private WorkCategoryResolution resolveForNewLog(ResolutionContextCache contexts, WorkLogFormDto dto) {
        WorkShift shift = requireShift(dto.getWorkShiftId());
        return contexts.forShift(shift).requireAllowed(dto.getWorkCodeCategoryId());
    }

    /**
     * The same for an edit, using the shift the log will belong to AFTER the edit.
     *
     * <p>An edit can move a log to a different shift, and therefore to a
     * different employee or work date — which can change both the applicable
     * scheme and the coefficient. Resolving against the incoming shift rather
     * than the stored one is what makes a moved log get re-priced correctly.
     */
    private WorkCategoryResolution resolveForExistingLog(ResolutionContextCache contexts,
                                                         WorkLog existing,
                                                         WorkLogFormDto dto) {
        WorkShift shift = dto.getWorkShiftId() != null
                ? requireShift(dto.getWorkShiftId())
                : existing.getWorkShift();
        return contexts.forShift(shift).requireAllowed(dto.getWorkCodeCategoryId());
    }

    private WorkShift requireShift(Long workShiftId) {
        if (workShiftId == null) {
            throw new IllegalArgumentException("workShiftId is required");
        }
        return workShiftRepository.findById(workShiftId)
                .orElseThrow(() -> new EntityNotFoundException("Work shift not found: " + workShiftId));
    }

    /** Memoises one resolution context per (employee, work date) for a batch. */
    private final class ResolutionContextCache {
        private final Map<String, WorkCategoryResolutionService.ResolutionContext> byEmployeeAndDate =
                new HashMap<>();

        WorkCategoryResolutionService.ResolutionContext forShift(WorkShift shift) {
            Long employeeId = shift.getEmployee() == null ? null : shift.getEmployee().getId();
            LocalDate workDate = shift.getWorkDate();
            if (employeeId == null || workDate == null) {
                throw new IllegalArgumentException(
                        "Work shift " + shift.getId() + " has no employee or work date");
            }
            return byEmployeeAndDate.computeIfAbsent(
                    employeeId + "@" + workDate,
                    key -> resolutionService.contextFor(employeeId, workDate));
        }
    }
}
