package com.aleksandarparipovic.marel_app.image;

import com.aleksandarparipovic.marel_app.file.FileMetadata;
import com.aleksandarparipovic.marel_app.file.FileRepository;
import com.aleksandarparipovic.marel_app.file.FileService;
import com.aleksandarparipovic.marel_app.image.dto.ImageAttachRequest;
import com.aleksandarparipovic.marel_app.image.dto.ImageDto;
import com.aleksandarparipovic.marel_app.image.dto.ImageUpdateRequest;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_family.ProductFamilyRepository;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Attaching images to families, types and products.
 *
 * <p>Upload and attach are two steps: the bytes go through {@code /api/files}
 * first, then this links the returned file to an owner. It keeps the two domain
 * rules the {@code images} table's constraints back up: at most one primary per
 * owner (cleared here before a new one is set, so the partial unique index never
 * has to reject anything), and a sensible order (new images go to the end).
 *
 * <p>Detaching removes the link. If nothing else references the file afterwards
 * it is an orphan, so the file (row and stored bytes) is deleted with it.
 */
@Service
@RequiredArgsConstructor
public class ImageService {

    private final ImageRepository imageRepository;
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final ProductFamilyRepository familyRepository;
    private final ProductTypeRepository typeRepository;
    private final ProductRepository productRepository;
    private final ImageMapper mapper;

    @Transactional(readOnly = true)
    public List<ImageDto> list(ImageOwnerType ownerType, Long ownerId) {
        requireOwner(ownerType, ownerId);
        return siblings(ownerType, ownerId).stream().map(mapper::toDto).toList();
    }

    @Transactional
    public ImageDto attach(ImageOwnerType ownerType, Long ownerId, ImageAttachRequest request) {
        requireOwner(ownerType, ownerId);
        FileMetadata file = fileRepository.findById(request.getFileId())
                .orElseThrow(() -> new EntityNotFoundException("Fajl nije pronađen: " + request.getFileId()));

        List<Image> existing = siblings(ownerType, ownerId);
        boolean makePrimary = request.getPrimary() != null ? request.getPrimary() : existing.isEmpty();
        int nextOrder = existing.stream().mapToInt(Image::getSortOrder).max().orElse(-1) + 1;

        if (makePrimary) {
            clearPrimaries(existing, null);
        }

        Image image = Image.builder()
                .file(file)
                .altText(blankToNull(request.getAltText()))
                .isPrimary(makePrimary)
                .sortOrder(nextOrder)
                .build();
        assignOwner(image, ownerType, ownerId);

        return mapper.toDto(imageRepository.save(image));
    }

    @Transactional
    public ImageDto update(Long imageId, ImageUpdateRequest request) {
        Image image = load(imageId);

        if (request.getAltText() != null) {
            image.setAltText(blankToNull(request.getAltText()));
        }
        if (request.getSortOrder() != null) {
            image.setSortOrder(request.getSortOrder());
        }
        if (request.getPrimary() != null) {
            if (request.getPrimary()) {
                clearPrimaries(siblingsOf(image), image.getId());
                image.setIsPrimary(true);
            } else {
                image.setIsPrimary(false);
            }
        }

        return mapper.toDto(imageRepository.save(image));
    }

    /** Detach the image. The underlying file is deleted too once nothing else points at it. */
    @Transactional
    public void detach(Long imageId) {
        Image image = load(imageId);
        Long fileId = image.getFile() != null ? image.getFile().getId() : null;

        imageRepository.delete(image);
        imageRepository.flush();

        if (fileId != null && imageRepository.countByFile_Id(fileId) == 0) {
            fileService.delete(fileId);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Image load(Long id) {
        return imageRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Slika nije pronađena: " + id));
    }

    private void requireOwner(ImageOwnerType ownerType, Long ownerId) {
        boolean exists = switch (ownerType) {
            case PRODUCT_FAMILY -> familyRepository.existsById(ownerId);
            case PRODUCT_TYPE -> typeRepository.existsById(ownerId);
            case PRODUCT -> productRepository.existsById(ownerId);
        };
        if (!exists) {
            throw new EntityNotFoundException("Vlasnik slike nije pronađen: " + ownerType + " " + ownerId);
        }
    }

    private List<Image> siblings(ImageOwnerType ownerType, Long ownerId) {
        return switch (ownerType) {
            case PRODUCT_FAMILY -> imageRepository.findByProductFamily_IdOrderBySortOrderAscIdAsc(ownerId);
            case PRODUCT_TYPE -> imageRepository.findByProductType_IdOrderBySortOrderAscIdAsc(ownerId);
            case PRODUCT -> imageRepository.findByProduct_IdOrderBySortOrderAscIdAsc(ownerId);
        };
    }

    /** The other images of whichever owner this image belongs to. */
    private List<Image> siblingsOf(Image image) {
        if (image.getProductFamily() != null) {
            return imageRepository.findByProductFamily_IdOrderBySortOrderAscIdAsc(image.getProductFamily().getId());
        }
        if (image.getProductType() != null) {
            return imageRepository.findByProductType_IdOrderBySortOrderAscIdAsc(image.getProductType().getId());
        }
        return imageRepository.findByProduct_IdOrderBySortOrderAscIdAsc(image.getProduct().getId());
    }

    /**
     * Clear the primary flag on an owner's images (except {@code exceptId}) and
     * flush, so the incoming primary does not collide with the outgoing one on
     * the partial unique index.
     */
    private void clearPrimaries(List<Image> owned, Long exceptId) {
        List<Image> toClear = owned.stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsPrimary()))
                .filter(i -> exceptId == null || !i.getId().equals(exceptId))
                .peek(i -> i.setIsPrimary(false))
                .toList();
        if (!toClear.isEmpty()) {
            imageRepository.saveAllAndFlush(toClear);
        }
    }

    private void assignOwner(Image image, ImageOwnerType ownerType, Long ownerId) {
        switch (ownerType) {
            case PRODUCT_FAMILY -> image.setProductFamily(familyRepository.getReferenceById(ownerId));
            case PRODUCT_TYPE -> image.setProductType(typeRepository.getReferenceById(ownerId));
            case PRODUCT -> image.setProduct(productRepository.getReferenceById(ownerId));
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
