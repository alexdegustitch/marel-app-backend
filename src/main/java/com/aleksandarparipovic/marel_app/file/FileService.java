package com.aleksandarparipovic.marel_app.file;

import com.aleksandarparipovic.marel_app.file.dto.FileDto;
import com.aleksandarparipovic.marel_app.file.storage.StorageProperties;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Uploading, reading and deleting stored files.
 *
 * <p><b>Never trusts the browser.</b> The declared content type is checked
 * against an allow-list, the size against a cap, and the KEY is generated on the
 * server (a random UUID under a folder) — the user's own filename is kept only
 * for display, never used to address the object. This is the guard the writeup
 * calls for: a hostile or careless client cannot choose where its bytes land or
 * make the server store an executable as an image.
 *
 * <p>Images are downscaled to {@value #MAX_DIMENSION}px on the long side before
 * storage, so an 8&nbsp;MP phone photo used as a 64px avatar does not cost 8&nbsp;MB.
 * WebP is passed through unresized (the JDK has no WebP reader), which is fine —
 * it is already a compact format.
 */
@Service
@RequiredArgsConstructor
public class FileService {

    private static final int MAX_DIMENSION = 1600;

    /** What the server accepts, and the extension each maps to. */
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    /** Folders are server-controlled category names: letters, digits, dash, slash. */
    private static final Pattern SAFE_FOLDER = Pattern.compile("[a-z0-9][a-z0-9/_-]*");
    private static final String DEFAULT_FOLDER = "uploads";

    private final FileRepository fileRepository;
    private final FileStorage storage;
    private final StorageProperties properties;

    /**
     * Validate, downscale and store an uploaded image, recording its metadata.
     *
     * @param folder a category prefix for the key (e.g. "products"); defaulted
     *               and sanitised — callers pass a constant, not user input
     * @throws IllegalArgumentException if the file is empty, too large, or not an allowed image
     */
    @Transactional
    public FileDto uploadImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Fajl je prazan.");
        }
        String contentType = file.getContentType();
        String extension = ALLOWED_IMAGE_TYPES.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Dozvoljene su samo slike (JPEG, PNG, WebP).");
        }
        if (file.getSize() > properties.getMaxImageBytes()) {
            long maxMb = properties.getMaxImageBytes() / (1024 * 1024);
            throw new IllegalArgumentException("Slika je prevelika (najviše " + maxMb + " MB).");
        }

        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Neuspešno čitanje uploadovanog fajla.", e);
        }
        byte[] stored = downscaleIfNeeded(original, contentType);

        String key = safeFolder(folder) + "/" + UUID.randomUUID() + "." + extension;
        storage.put(key, stored, contentType);

        FileMetadata saved = fileRepository.save(FileMetadata.builder()
                .storageKey(key)
                .originalFilename(file.getOriginalFilename())
                .contentType(contentType)
                .sizeBytes((long) stored.length)
                .build());

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public FileDto get(Long id) {
        return toDto(load(id));
    }

    /** The bytes and just enough to serve them; the caller streams and closes. */
    @Transactional(readOnly = true)
    public FileContent download(Long id) {
        FileMetadata meta = load(id);
        InputStream stream = storage.openStream(meta.getStorageKey());
        return new FileContent(meta.getOriginalFilename(), meta.getContentType(), meta.getSizeBytes(), stream);
    }

    /** Delete the object and its row together. Image links to it cascade away. */
    @Transactional
    public void delete(Long id) {
        FileMetadata meta = load(id);
        storage.delete(meta.getStorageKey());
        fileRepository.delete(meta);
    }

    private FileMetadata load(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Fajl nije pronađen: " + id));
    }

    private FileDto toDto(FileMetadata f) {
        return FileDto.builder()
                .id(f.getId())
                .originalFilename(f.getOriginalFilename())
                .contentType(f.getContentType())
                .sizeBytes(f.getSizeBytes())
                .createdAt(f.getCreatedAt())
                .downloadUrl("/api/files/" + f.getId() + "/content")
                .build();
    }

    /**
     * Downscale JPEG/PNG to {@value #MAX_DIMENSION}px on the long side. An image
     * already within bounds, an unreadable one, or a WebP (no JDK reader) is
     * stored as it arrived.
     */
    private byte[] downscaleIfNeeded(byte[] bytes, String contentType) {
        if ("image/webp".equals(contentType)) {
            return bytes;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null
                    || (image.getWidth() <= MAX_DIMENSION && image.getHeight() <= MAX_DIMENSION)) {
                return bytes;
            }
            String outputFormat = "image/png".equals(contentType) ? "png" : "jpg";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(image)
                    .size(MAX_DIMENSION, MAX_DIMENSION)
                    .keepAspectRatio(true)
                    .outputFormat(outputFormat)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            // A resize failure is not a reason to lose the upload; keep the original.
            return bytes;
        }
    }

    private static String safeFolder(String folder) {
        if (folder == null) return DEFAULT_FOLDER;
        String trimmed = folder.trim().toLowerCase();
        return SAFE_FOLDER.matcher(trimmed).matches() ? trimmed : DEFAULT_FOLDER;
    }

    /** A file's bytes plus what a response needs to serve them. */
    public record FileContent(String filename, String contentType, Long sizeBytes, InputStream stream) {
    }
}
