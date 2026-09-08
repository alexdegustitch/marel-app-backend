package com.aleksandarparipovic.marel_app.file;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The metadata row for one stored object. The bytes live in object storage; this
 * carries the key that finds them, plus what the file is.
 *
 * <p>Named FileMetadata, not File, to stay clear of {@link java.io.File}. Mapped
 * to the {@code files} table. {@code createdAt} is the database's default.
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The object key inside the bucket. Unique. Never a full provider URL. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** The account that uploaded it; null once that account is gone (ON DELETE SET NULL). */
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
