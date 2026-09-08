package com.aleksandarparipovic.marel_app.file.storage;

import com.aleksandarparipovic.marel_app.file.FileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

/**
 * Production storage: Cloudflare R2 over the S3 API. Active only when
 * {@code app.storage.type=r2}.
 *
 * <p>The bucket is configuration, not part of any key — {@code storage_key} is
 * always just the object path inside the bucket, which is what lets the same key
 * move to another provider untouched.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "r2")
public class R2FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;

    public R2FileStorage(S3Client r2S3Client, StorageProperties properties) {
        this.s3 = r2S3Client;
        this.bucket = properties.getR2().getBucket();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public InputStream openStream(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }
}
