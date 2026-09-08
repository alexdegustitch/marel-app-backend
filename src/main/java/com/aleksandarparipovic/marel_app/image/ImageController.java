package com.aleksandarparipovic.marel_app.image;

import com.aleksandarparipovic.marel_app.image.dto.ImageAttachRequest;
import com.aleksandarparipovic.marel_app.image.dto.ImageDto;
import com.aleksandarparipovic.marel_app.image.dto.ImageUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Attaching images to an owner and maintaining them.
 *
 * <p>Listing and attaching are addressed from the owner, whose kind is the first
 * path segment — {@code /api/product-families/{id}/images},
 * {@code /api/product-types/{id}/images} or {@code /api/products/{id}/images}.
 * An individual image is then updated or detached by its own id.
 *
 * <p>No matcher of its own in {@code SecurityConfig} — {@code anyRequest().authenticated()}.
 */
@RestController
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @GetMapping("/api/{ownerType}/{ownerId}/images")
    public ResponseEntity<List<ImageDto>> list(
            @PathVariable String ownerType,
            @PathVariable Long ownerId
    ) {
        return ResponseEntity.ok(imageService.list(ImageOwnerType.fromPathSegment(ownerType), ownerId));
    }

    @PostMapping("/api/{ownerType}/{ownerId}/images")
    public ResponseEntity<ImageDto> attach(
            @PathVariable String ownerType,
            @PathVariable Long ownerId,
            @Valid @RequestBody ImageAttachRequest request
    ) {
        return ResponseEntity.ok(
                imageService.attach(ImageOwnerType.fromPathSegment(ownerType), ownerId, request));
    }

    @PatchMapping("/api/images/{id}")
    public ResponseEntity<ImageDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ImageUpdateRequest request
    ) {
        return ResponseEntity.ok(imageService.update(id, request));
    }

    /** Detach. Removes the link and, once the file has no other links, the file too. */
    @DeleteMapping("/api/images/{id}")
    public ResponseEntity<Void> detach(@PathVariable Long id) {
        imageService.detach(id);
        return ResponseEntity.noContent().build();
    }
}
