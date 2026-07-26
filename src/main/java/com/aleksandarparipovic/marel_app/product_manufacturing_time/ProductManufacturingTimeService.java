package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeOperationRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.ProductManufacturingTimeOperation;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.ProductManufacturingTimeOperationRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
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
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OperationRepository operationRepository;
    private final ProductManufacturingTimeOperationRepository pmtoRepository;

    @Transactional
    public ProductManufacturingTimeDto create(ProductManufacturingTimeCreateRequest req, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        ProductManufacturingTime entity = new ProductManufacturingTime();
        entity.setUser(user);
        entity.setTitle(req.getTitle());
        entity.setProduct(product);
        entity.setProductName(req.getProductName());
        entity.setDateOfIssue(LocalDate.now());
        entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        entity.setProductsPerHour(req.getProductsPerHour());
        entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        entity.setActive(true);

        ProductManufacturingTime saved = repository.save(entity);
        saveOperations(saved, req.getOperations());
        return toDto(saved);
    }

    /**
     * Same as {@link #create} but with the acting user passed explicitly, for the
     * request workflow where the processor is resolved from the request rather
     * than re-read from the Authentication. Returns the entity so the caller can
     * link it to its source request inside the same transaction.
     */
    @Transactional
    public ProductManufacturingTime createForUser(ProductManufacturingTimeCreateRequest req, User user) {
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));

        ProductManufacturingTime entity = new ProductManufacturingTime();
        entity.setUser(user);
        entity.setTitle(req.getTitle());
        entity.setProduct(product);
        entity.setProductName(req.getProductName());
        entity.setDateOfIssue(LocalDate.now());
        entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        entity.setProductsPerHour(req.getProductsPerHour());
        entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        entity.setActive(true);

        ProductManufacturingTime saved = repository.save(entity);
        saveOperations(saved, req.getOperations());
        return saved;
    }

    /** Applies an update and returns the entity, for the request workflow. */
    @Transactional
    public ProductManufacturingTime applyUpdate(Long id, ProductManufacturingTimeUpdateRequest req) {
        update(id, req);
        return getActiveOrThrow(id);
    }

    @Transactional
    public ProductManufacturingTimeDto update(Long id, ProductManufacturingTimeUpdateRequest req) {
        ProductManufacturingTime entity = getActiveOrThrow(id);

        if (req.getManufacturingCoefficient() != null) entity.setManufacturingCoefficient(req.getManufacturingCoefficient());
        if (req.getProductsPerHour() != null) entity.setProductsPerHour(req.getProductsPerHour());
        if (req.getManufacturingTimeSeconds() != null) entity.setManufacturingTimeSeconds(req.getManufacturingTimeSeconds());
        if (req.getTitle() != null) entity.setTitle(req.getTitle());

        if (req.getOperations() != null) {
            pmtoRepository.deactivateAllByProductManufacturingTimeId(entity.getId());
            saveOperations(entity, req.getOperations());
        }

        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public ProductManufacturingTimeDto getById(Long id) {
        return toDto(getActiveOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByUserId(Long userId) {
        return repository.findByUser_IdAndActiveTrueOrderByDateOfIssueDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getForCurrentUser(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return repository.findByUser_IdAndActiveTrueOrderByDateOfIssueDesc(user.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByProductId(Long productId) {
        return repository.findByProduct_IdAndActiveTrue(productId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductManufacturingTimeDto> getByProductIdAndDateRange(Long productId, LocalDate from, LocalDate to) {
        return repository.findByProductIdAndDateRange(productId, from, to)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        ProductManufacturingTime entity = getActiveOrThrow(id);
        entity.setActive(false);
        pmtoRepository.deactivateAllByProductManufacturingTimeId(id);
    }

    public ProductManufacturingTime getActiveOrThrow(Long id) {
        ProductManufacturingTime entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ProductManufacturingTime not found with id: " + id));
        if (!Boolean.TRUE.equals(entity.getActive())) {
            throw new EntityNotFoundException("ProductManufacturingTime not found with id: " + id);
        }
        return entity;
    }

    private void saveOperations(ProductManufacturingTime pmt, List<ProductManufacturingTimeOperationRequest> requests) {
        if (requests == null || requests.isEmpty()) return;

        for (ProductManufacturingTimeOperationRequest opReq : requests) {
            Operation operation = operationRepository.findById(opReq.getOperationId())
                    .orElseThrow(() -> new EntityNotFoundException("Operation not found with id: " + opReq.getOperationId()));

            ProductManufacturingTimeOperation op = new ProductManufacturingTimeOperation();
            op.setProductManufacturingTime(pmt);
            op.setOperation(operation);
            op.setOperationName(opReq.getOperationName());
            op.setUnitsPerProductSnapshot(opReq.getUnitsPerProductSnapshot());
            op.setUnitsPerProductOverridden(Boolean.TRUE.equals(opReq.getUnitsPerProductOverridden()));
            op.setUnitsPerProductValue(opReq.getUnitsPerProductValue());
            op.setNormSnapshot(opReq.getNormSnapshot());
            op.setNormOverridden(Boolean.TRUE.equals(opReq.getNormOverridden()));
            op.setNormValue(opReq.getNormValue());
            op.setNormDateSnapshot(opReq.getNormDateSnapshot());
            op.setNormDateOverridden(Boolean.TRUE.equals(opReq.getNormDateOverridden()));
            op.setNormDateValue(opReq.getNormDateValue());
            op.setExcluded(Boolean.TRUE.equals(opReq.getExcluded()));
            op.setActive(true);
            pmtoRepository.save(op);
        }
    }

    private ProductManufacturingTimeDto toDto(ProductManufacturingTime entity) {
        List<ProductManufacturingTimeOperationDto> operations =
                pmtoRepository.findByProductManufacturingTime_IdAndActiveTrue(entity.getId())
                        .stream()
                        .map(ProductManufacturingTimeOperationDto::new)
                        .toList();
        return new ProductManufacturingTimeDto(entity, operations);
    }
}
