package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductManufacturingTimeService {

    private final ProductManufacturingTimeRepository repository;
    private final OperationRepository operationRepository;
    private final UserRepository userRepository;

    @Transactional
    public ProductManufacturingTimeDto create(ProductManufacturingTimeCreateRequest req, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Operation operation = operationRepository.findById(req.getOperationId())
                .orElseThrow(() -> new EntityNotFoundException("Operation not found"));

        ProductManufacturingTime entity = new ProductManufacturingTime();
        entity.setUser(user);
        entity.setOperation(operation);
        entity.setOperationName(operation.getOpName());
        entity.setManufacturingDate(req.getManufacturingDate());

        entity.setUnitsPerProductSnapshot(req.getUnitsPerProductSnapshot());
        entity.setUnitsPerProductOverridden(Boolean.TRUE.equals(req.getUnitsPerProductOverridden()));
        entity.setUnitsPerProductValue(req.getUnitsPerProductValue());

        entity.setNormSnapshot(req.getNormSnapshot());
        entity.setNormOverridden(Boolean.TRUE.equals(req.getNormOverridden()));
        entity.setNormValue(req.getNormValue());

        entity.setNormDateSnapshot(req.getNormDateSnapshot());
        entity.setNormDateOverridden(Boolean.TRUE.equals(req.getNormDateOverridden()));
        entity.setNormDateValue(req.getNormDateValue());

        entity.setExcluded(Boolean.TRUE.equals(req.getExcluded()));
        entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        entity.setProductsPerHour(req.getProductsPerHour());
        entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        entity.setActive(true);

        return new ProductManufacturingTimeDto(repository.save(entity));
    }

    @Transactional
    public ProductManufacturingTimeDto update(Long id, ProductManufacturingTimeUpdateRequest req) {
        ProductManufacturingTime entity = getActiveOrThrow(id);

        if (req.getManufacturingDate() != null) entity.setManufacturingDate(req.getManufacturingDate());

        if (req.getUnitsPerProductSnapshot() != null) entity.setUnitsPerProductSnapshot(req.getUnitsPerProductSnapshot());
        if (req.getUnitsPerProductOverridden() != null) entity.setUnitsPerProductOverridden(req.getUnitsPerProductOverridden());
        if (req.getUnitsPerProductValue() != null) entity.setUnitsPerProductValue(req.getUnitsPerProductValue());

        if (req.getNormSnapshot() != null) entity.setNormSnapshot(req.getNormSnapshot());
        if (req.getNormOverridden() != null) entity.setNormOverridden(req.getNormOverridden());
        if (req.getNormValue() != null) entity.setNormValue(req.getNormValue());

        if (req.getNormDateSnapshot() != null) entity.setNormDateSnapshot(req.getNormDateSnapshot());
        if (req.getNormDateOverridden() != null) entity.setNormDateOverridden(req.getNormDateOverridden());
        if (req.getNormDateValue() != null) entity.setNormDateValue(req.getNormDateValue());

        if (req.getExcluded() != null) entity.setExcluded(req.getExcluded());
        if (req.getManufacturingCoefficient() != null) entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        if (req.getProductsPerHour() != null) entity.setProductsPerHour(req.getProductsPerHour());
        if (req.getManufacturingTimeSeconds() != null) entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());

        return new ProductManufacturingTimeDto(entity);
    }

    @Transactional(readOnly = true)
    public ProductManufacturingTimeDto getById(Long id) {
        return new ProductManufacturingTimeDto(getActiveOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByOperationId(Long operationId) {
        return repository.findByOperation_IdAndActiveTrue(operationId)
                .stream()
                .map(ProductManufacturingTimeDto::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByOperationIdAndDateRange(Long operationId, LocalDate from, LocalDate to) {
        return repository.findByOperationIdAndDateRange(operationId, from, to)
                .stream()
                .map(ProductManufacturingTimeDto::new)
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        ProductManufacturingTime entity = getActiveOrThrow(id);
        entity.setActive(false);
    }

    private ProductManufacturingTime getActiveOrThrow(Long id) {
        ProductManufacturingTime entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ProductManufacturingTime not found with id: " + id));
        if (!Boolean.TRUE.equals(entity.getActive())) {
            throw new EntityNotFoundException("ProductManufacturingTime not found with id: " + id);
        }
        return entity;
    }
}

