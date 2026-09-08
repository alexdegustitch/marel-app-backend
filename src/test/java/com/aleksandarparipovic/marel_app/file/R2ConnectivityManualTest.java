package com.aleksandarparipovic.marel_app.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A one-off, opt-in check that the R2 credentials in the environment actually
 * talk to the staging bucket: put a tiny object, read it back, delete it.
 *
 * <p>Disabled unless {@code R2_SMOKE=1}, so a normal {@code mvn test} skips it and
 * it never touches R2 in CI. Reads R2_* from the environment (never printed), the
 * same values the app binds from .env. Cleans up the object it wrote.
 */
@EnabledIfEnvironmentVariable(named = "R2_SMOKE", matches = "1")
class R2ConnectivityManualTest {

    @Test
    void putGetDeleteRoundTrip() {
        String endpoint = System.getenv("R2_ENDPOINT");
        String bucket = System.getenv("R2_BUCKET");
        String region = System.getenv().getOrDefault("R2_REGION", "auto");

        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("R2_ACCESS_KEY_ID"),
                        System.getenv("R2_SECRET_ACCESS_KEY"))))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        String key = "__smoketest__/" + UUID.randomUUID() + ".txt";
        byte[] payload = "spikytail r2 ok".getBytes(StandardCharsets.UTF_8);

        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").build(),
                    RequestBody.fromBytes(payload));

            ResponseBytes<?> read = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());

            assertThat(read.asByteArray()).isEqualTo(payload);
            System.out.println("[R2 SMOKE] put/get/delete OK — bucket reachable, credentials valid.");
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (RuntimeException ignored) {
                // Cleanup best-effort; a left-over test object is harmless.
            }
            s3.close();
        }
    }
}
