package com.aleksandarparipovic.marel_app.file.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Storage configuration, bound from {@code app.storage.*}.
 *
 * <p>{@code type} chooses the {@link com.aleksandarparipovic.marel_app.file.FileStorage}
 * implementation: {@code local} (default, disk under {@code local.dir}) for dev,
 * {@code r2} (Cloudflare R2 or any S3-compatible endpoint) for prod. The R2
 * fields are read from the environment and are absent in dev, which is why the
 * R2 client is only wired when {@code type=r2}.
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** local | r2. Defaults to local so dev needs no credentials. */
    private String type = "local";

    /** The largest image the server will accept, in bytes. */
    private long maxImageBytes = 8L * 1024 * 1024;

    private final Local local = new Local();
    private final R2 r2 = new R2();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }

    public Local getLocal() { return local; }
    public R2 getR2() { return r2; }

    public static class Local {
        /** Base directory the keys are written under. */
        private String dir = "./uploads";
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class R2 {
        /** https://<account-id>.r2.cloudflarestorage.com */
        private String endpoint;
        private String accessKeyId;
        private String secretAccessKey;
        private String bucket;
        /** R2 ignores the region but the SDK requires one; "auto" is conventional. */
        private String region = "auto";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }
}
