package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The same four workbooks, read out of an S3 bucket.
 *
 * <p>The folder source only ever worked on a developer's machine: Render's disk
 * is ephemeral and is not somewhere the RE team can drop a file, so on the
 * deployed console CSAT had nowhere to read from. A bucket is what the
 * interface was always waiting for — the team uploads the month's workbooks,
 * overwriting last month's as they do today, and nothing else about CSAT
 * changes: same parser, same cache, same reload button.
 *
 * <p>S3 rather than Supabase's own storage API, although the bucket is a
 * Supabase one today: Supabase Storage answers S3 through a gateway, so this
 * one client reads that bucket now and an AWS bucket later with nothing but an
 * endpoint and a key pair changing. Two calls are all it takes — list the
 * prefix, get an object — so nothing here is vendor-shaped.
 *
 * <p>The credentials stay on the backend: never logged, never part of
 * {@link #describe()}, never in an API response. Keep the bucket private, too —
 * the workbooks name customers, and a public bucket serves them to anyone
 * holding the URL.
 */
@Slf4j
public class BucketCsatWorkbookSource implements CsatWorkbookSource {

    /** Four workbooks a month; this only stops a misconfigured prefix paging a whole bucket. */
    private static final int LIST_LIMIT = 200;

    private final KpiCsatProperties.Bucket bucket;
    private final S3Client s3;

    /** The last listing, reused for a few seconds; see {@link #list()}. */
    private volatile Listing listing;

    public BucketCsatWorkbookSource(KpiCsatProperties properties) {
        this.bucket = properties.getBucket();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(bucket.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(bucket.getAccessKey(), bucket.getSecretKey())))
                // Supabase and MinIO only answer endpoint/bucket/key; AWS accepts it.
                .forcePathStyle(bucket.isPathStyle())
                // Timeouts, for the reason the monday client has them: without one a
                // request waits on the socket forever, and the CSAT page waits with it.
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(Duration.ofSeconds(bucket.getReadTimeoutSeconds()))
                        .apiCallTimeout(Duration.ofSeconds(
                                (long) bucket.getReadTimeoutSeconds() + bucket.getConnectTimeoutSeconds())));
        String endpoint = bucket.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        this.s3 = builder.build();
    }

    @Override
    public String describe() {
        String prefix = prefix();
        return "bucket " + bucket.getName() + (prefix.isEmpty() ? "" : "/" + prefix);
    }

    /**
     * The bucket's workbooks, reusing the last listing for a few seconds.
     *
     * <p>Every request for the CSAT page lists the source to decide whether to
     * re-parse, which over a folder is a few stats and over a bucket is a
     * round trip to another service. The window is short enough that an upload
     * appears on its own within a minute, and {@link #refresh()} — the console's
     * reload button — skips it entirely.
     */
    @Override
    public List<WorkbookFile> list() {
        Listing current = listing;
        if (current != null && current.isFresh(bucket.getListCacheSeconds())) {
            return current.files();
        }
        List<WorkbookFile> files = fetchListing();
        if (files.isEmpty()) {
            throw new BadRequestException("No .xlsx workbooks in " + describe()
                    + ". The RE team uploads the four survey workbooks there.");
        }
        listing = new Listing(Instant.now(), files);
        return files;
    }

    /** The reload button: forget the listing, so the next read goes to the bucket. */
    @Override
    public void refresh() {
        listing = null;
    }

    /** Spring closes the bean on shutdown, which closes the client's connections. */
    public void close() {
        s3.close();
    }

    private List<WorkbookFile> fetchListing() {
        String prefix = prefix();
        List<S3Object> objects;
        try {
            objects = s3.listObjectsV2(request -> request
                    .bucket(bucket.getName())
                    .prefix(prefix.isEmpty() ? null : prefix + "/")
                    .maxKeys(LIST_LIMIT)).contents();
        } catch (SdkException e) {
            throw new BadRequestException(explain(e, "list"));
        }

        List<WorkbookFile> files = new ArrayList<>();
        for (S3Object object : objects) {
            String key = object.key();
            String name = key.substring(key.lastIndexOf('/') + 1);
            // A key ending in / is a folder marker, not a workbook.
            if (!isWorkbook(name)) {
                continue;
            }
            files.add(new WorkbookFile(name, object.size(), object.lastModified(), () -> download(key)));
        }
        log.info("CSAT workbooks listed from {}: {} files", describe(), files.size());
        return files;
    }

    /**
     * The bytes, held in memory: four small workbooks, POI reads each one whole
     * anyway, and streaming from the socket into the parser would hold a
     * connection open for the length of a parse.
     */
    private InputStream download(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes =
                    s3.getObjectAsBytes(request -> request.bucket(bucket.getName()).key(key));
            return bytes.asInputStream();
        } catch (SdkException e) {
            throw new BadRequestException(explain(e, key));
        }
    }

    /**
     * What went wrong, in words the console can show. The SDK's own message is
     * kept out of it on purpose: it quotes the request, and the request carries
     * the credentials.
     */
    private String explain(SdkException e, String what) {
        if (e instanceof NoSuchBucketException) {
            return "The CSAT " + describe() + " does not exist. Check the bucket name, the region and "
                    + "the endpoint.";
        }
        if (e instanceof NoSuchKeyException) {
            return "The CSAT workbook " + what + " is no longer in " + describe() + ". Reload to list it again.";
        }
        if (e instanceof S3Exception s3Exception) {
            int status = s3Exception.statusCode();
            if (status == 401 || status == 403) {
                return "The CSAT bucket refused the credentials (" + status + "). Check the access key and "
                        + "secret, and that they belong to the project or account holding " + describe() + ".";
            }
            return "The CSAT bucket answered " + status + " (" + describe() + ")";
        }
        return "Could not reach the CSAT bucket (" + describe() + "): " + e.getClass().getSimpleName();
    }

    /** Same rule as the folder: .xlsx only, and never Excel's ~$ lock files. */
    private static boolean isWorkbook(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.startsWith("~$");
    }

    private String prefix() {
        String value = bucket.getPrefix() == null ? "" : bucket.getPrefix().trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record Listing(Instant at, List<WorkbookFile> files) {
        boolean isFresh(int seconds) {
            return seconds > 0 && Instant.now().isBefore(at.plusSeconds(seconds));
        }
    }
}
