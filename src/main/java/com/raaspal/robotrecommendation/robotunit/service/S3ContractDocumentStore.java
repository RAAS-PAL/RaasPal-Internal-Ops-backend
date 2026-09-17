package com.raaspal.robotrecommendation.robotunit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Contract PDFs in a private S3 bucket.
 *
 * <p>Present only when {@code app.contracts.s3.bucket} is set; a local run without
 * it gets {@link UnconfiguredContractDocumentStore} and a clear message instead of
 * a stack trace from the SDK. Credentials come from {@code AWS_ACCESS_KEY_ID} /
 * {@code AWS_SECRET_ACCESS_KEY} in the container's environment - the dedicated IAM
 * user whose policy reaches this one bucket and nothing else.
 *
 * <p>Downloads are pre-signed links, five minutes long: the bucket blocks all public
 * access, so a link is the bucket letting one reader in for one file, and it stops
 * working before it can usefully be forwarded.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${app.contracts.s3.bucket:}' != ''")
public class S3ContractDocumentStore implements ContractDocumentStore {

    private static final Duration LINK_LIFETIME = Duration.ofMinutes(5);

    private final String bucket;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ContractDocumentStore(@Value("${app.contracts.s3.bucket}") String bucket,
                                   @Value("${app.contracts.s3.region}") String region,
                                   @Value("${app.contracts.s3.access-key-id:}") String accessKeyId,
                                   @Value("${app.contracts.s3.secret-access-key:}") String secretAccessKey) {
        this.bucket = bucket;
        Region awsRegion = Region.of(region);
        if (!accessKeyId.isBlank()) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
            this.s3 = S3Client.builder().region(awsRegion).credentialsProvider(credentials).build();
            this.presigner = S3Presigner.builder().region(awsRegion).credentialsProvider(credentials).build();
        } else {
            // The SDK's default chain: environment, profile, instance role.
            this.s3 = S3Client.builder().region(awsRegion).build();
            this.presigner = S3Presigner.builder().region(awsRegion).build();
        }
        log.info("Contract documents: S3 bucket {} in {}", bucket, region);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public String temporaryUrl(String key, String downloadFileName) {
        // RFC 5987 so a Thai filename survives the header.
        String encoded = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8).replace("+", "%20");
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition("inline; filename*=UTF-8''" + encoded)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(LINK_LIFETIME)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
