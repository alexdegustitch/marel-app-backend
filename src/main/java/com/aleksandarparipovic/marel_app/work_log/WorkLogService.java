package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.dto.CreateUpdateWorkLogsRequest;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogFormDto;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkLogService {

    private final WorkLogRepository repository;
    private final WorkLogMapper workLogMapper;


    public List<WorkLogDto> fetchAllActiveLogsForShift(Long shiftId){
        return repository.getAllActiveLogsForShift(shiftId);
    }

    @Transactional
    public List<WorkLogDto> handleBatch(CreateUpdateWorkLogsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        List<WorkLog> result = new ArrayList<>();

        if (request.getCreate() != null) {
            List<WorkLog> toCreate = request.getCreate()
                    .stream()
                    .map(workLogMapper::toEntity)
                    .toList();

            result.addAll(repository.saveAll(toCreate));
        }

        if (request.getUpdate() != null && !request.getUpdate().isEmpty()) {
            List<Long> ids = request.getUpdate().stream()
                    .map(WorkLogFormDto::getId)
                    .peek(id -> {
                        if (id == null) {
                            throw new IllegalArgumentException("Work log id is required for update");
                        }
                    })
                    .toList();

            Map<Long, WorkLog> existingMap = repository.findAllById(ids).stream()
                    .collect(Collectors.toMap(WorkLog::getId, Function.identity()));

            List<WorkLog> toUpdate = new ArrayList<>();

            for (WorkLogFormDto dto : request.getUpdate()) {
                WorkLog existing = existingMap.get(dto.getId());
                if (existing == null) {
                    throw new EntityNotFoundException("Work log not found: " + dto.getId());
                }

                workLogMapper.updateEntity(existing, dto);
                toUpdate.add(existing);
            }

            result.addAll(toUpdate);
        }

        if (request.getDeleted() != null && !request.getDeleted().isEmpty()) {
            List<Long> ids = request.getDeleted()
                    .stream()
                    .map(WorkLogFormDto::getId)
                    .peek(id -> {
                        if(id == null){
                            throw new IllegalArgumentException("Work log id is required for delete");
                        }
                    })
                    .toList();
            Map<Long, WorkLog> existingMap = repository.findAllById(ids)
                    .stream()
                    .collect(Collectors.toMap(WorkLog::getId, Function.identity()));

            List<WorkLog> toDelete = new ArrayList<>();

            for (WorkLogFormDto dto : request.getDeleted()) {
                WorkLog existing = existingMap.get(dto.getId());
                if (existing == null) {
                    throw new EntityNotFoundException("Work log not found: " + dto.getId());
                }

                existing.setIsActive(false);
                toDelete.add(existing);
            }

            result.addAll(toDelete);
        }

        return result.stream().map(workLogMapper::toDto).toList();
    }
}
