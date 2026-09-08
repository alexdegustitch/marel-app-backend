package com.aleksandarparipovic.marel_app.file;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading and serving files.
 *
 * <p>Upload returns the file's metadata (with a {@code downloadUrl}); a later
 * step attaches it to a product, type or family. Content is streamed back
 * through the backend for both storage backends — a private bucket never has a
 * public URL. (Presigned GET URLs are the natural next optimisation for R2, to
 * take the bytes off the backend; the FileStorage seam is where that goes.)
 *
 * <p>No matcher of its own in {@code SecurityConfig} — {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /** Upload one image. {@code folder} is an optional category prefix ("products"). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String folder
    ) {
        return ResponseEntity.ok(fileService.uploadImage(file, folder));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ResponseEntity.ok(fileService.get(id));
    }

    /** Stream the bytes. Served inline so an <img src> can point straight at it. */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long id) {
        FileService.FileContent file = fileService.download(id);

        HttpHeaders headers = new HttpHeaders();
        if (file.contentType() != null) {
            headers.setContentType(MediaType.parseMediaType(file.contentType()));
        }
        if (file.sizeBytes() != null) {
            headers.setContentLength(file.sizeBytes());
        }
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(file.filename() != null ? file.filename() : "file")
                .build());

        return new ResponseEntity<>(new InputStreamResource(file.stream()), headers, 200);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
