package com.aleksandarparipovic.marel_app.file;

import java.io.InputStream;

/**
 * Where the bytes of an uploaded file actually live.
 *
 * <p>The rest of the application never knows whether that is Cloudflare R2, AWS
 * S3, MinIO or the local disk — it holds only the {@code storage_key} and asks
 * this seam to put, read or delete by that key. Swapping provider swaps the
 * implementation and nothing else; no stored row changes, because a row holds the
 * key, never a provider URL.
 */
public interface FileStorage {

    /** Store {@code content} under {@code key}, overwriting any object already there. */
    void put(String key, byte[] content, String contentType);

    /** Open the stored object for reading. The caller closes the stream. */
    InputStream openStream(String key);

    /** Remove the stored object. A key that is already gone is not an error. */
    void delete(String key);
}
