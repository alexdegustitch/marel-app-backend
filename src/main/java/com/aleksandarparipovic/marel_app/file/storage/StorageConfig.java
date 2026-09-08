package com.aleksandarparipovic.marel_app.file.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * The S3 client that talks to Cloudflare R2 — wired only when
 * {@code app.storage.type=r2}, so dev (and any environment without R2
 * credentials) starts without it and falls to {@link LocalFileStorage}.
 *
 * <p>R2 is reached by overriding the endpoint; path-style access is enabled
 * because R2 addresses buckets in the path, not as a host prefix. The region is
 * a required-but-ignored field for R2 — "auto" by convention.
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "r2")
public class StorageConfig {

    @Bean
    public S3Client r2S3Client(StorageProperties properties) {
        StorageProperties.R2 r2 = properties.getR2();
        return S3Client.builder()
                .endpointOverride(URI.create(r2.getEndpoint()))
                .region(Region.of(r2.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKeyId(), r2.getSecretAccessKey())))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
