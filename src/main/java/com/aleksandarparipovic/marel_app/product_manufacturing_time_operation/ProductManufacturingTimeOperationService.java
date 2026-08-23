package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeService;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductManufacturingTimeOperationService {

    private final ProductManufacturingTimeOperationRepository repository;
    private final ProductManufacturingTimeService productManufacturingTimeService;
    private final OperationRepository operationRepository;

    @Transactional
    public ProductManufacturingTimeOperationDto create(ProductManufacturingTimeOperationCreateRequest req) {
        ProductManufacturingTime pmt = productManufacturingTimeService
                .getActiveOrThrow(req.getProductManufacturingTimeId());

        Operation operation = operationRepository.findById(req.getOperationId())
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        ProductManufacturingTimeOperation entity = new ProductManufacturingTimeOperation();
        entity.setProductManufacturingTime(pmt);
        entity.setOperation(operation);
        entity.setOperationName(req.getOperationName());

        entity.setUnitsPerProductSnapshot(req.getUnitsPerProductSnapshot());
        entity.setUnitsPerProductOverridden(Boolean.TRUE.equals(req.getUnitsPerProductOverridden()));
        entity.setUnitsPerProductValue(req.getUnitsPerProductValue());

        entity.setNormSnapshot(req.getNormSnapshot());
        entity.setNormOverridden(Boolean.TRUE.equals(req.getNormOverridden()));
        entity.setNormValue(req.getNormValue());

        entity.setNormDateSnapshot(req.getNormDateSnapshot());
        entity.setNormDateOverridden(Boolean.TRUE.equals(req.getNormDateOverridden()));
        entity.setNormDateValue(req.getNormDateValue());
        entity.setNormDateNote(req.getNormDateNote());
        entity.setNote(req.getNote());

        entity.setExcluded(Boolean.TRUE.equals(req.getExcluded()));
        entity.setActive(true);

        return new ProductManufacturingTimeOperationDto(repository.save(entity));
    }

    @Transactional
    public ProductManufacturingTimeOperationDto update(Long id, ProductManufacturingTimeOperationUpdateRequest req) {
        ProductManufacturingTimeOperation entity = getActiveOrThrow(id);

        if (req.getUnitsPerProductSnapshot() != null) entity.setUnitsPerProductSnapshot(req.getUnitsPerProductSnapshot());
        if (req.getUnitsPerProductOverridden() != null) entity.setUnitsPerProductOverridden(req.getUnitsPerProductOverridden());
        if (req.getUnitsPerProductValue() != null) entity.setUnitsPerProductValue(req.getUnitsPerProductValue());

        if (req.getNormSnapshot() != null) entity.setNormSnapshot(req.getNormSnapshot());
        if (req.getNormOverridden() != null) entity.setNormOverridden(req.getNormOverridden());
        if (req.getNormValue() != null) entity.setNormValue(req.getNormValue());

        if (req.getNormDateSnapshot() != null) entity.setNormDateSnapshot(req.getNormDateSnapshot());
        if (req.getNormDateOverridden() != null) entity.setNormDateOverridden(req.getNormDateOverridden());
        if (req.getNormDateValue() != null) entity.setNormDateValue(req.getNormDateValue());
        if (req.getNormDateNote() != null) entity.setNormDateNote(req.getNormDateNote());
        if (req.getNote() != null) entity.setNote(req.getNote());

        if (req.getExcluded() != null) entity.setExcluded(req.getExcluded());

        return new ProductManufacturingTimeOperationDto(entity);
    }

    @Transactional(readOnly = true)
    public ProductManufacturingTimeOperationDto getById(Long id) {
        return new ProductManufacturingTimeOperationDto(getActiveOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeOperationDto> getByProductManufacturingTimeId(Long productManufacturingTimeId) {
        return repository.findByProductManufacturingTime_IdAndActiveTrue(productManufacturingTimeId)
                .stream()
                .map(ProductManufacturingTimeOperationDto::new)
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        ProductManufacturingTimeOperation entity = getActiveOrThrow(id);
        entity.setActive(false);
    }

    private ProductManufacturingTimeOperation getActiveOrThrow(Long id) {
        ProductManufacturingTimeOperation entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ProductManufacturingTimeOperation not found with id: " + id));
        if (!Boolean.TRUE.equals(entity.getActive())) {
            throw new EntityNotFoundException("ProductManufacturingTimeOperation not found with id: " + id);
        }
        return entity;
    }
}

