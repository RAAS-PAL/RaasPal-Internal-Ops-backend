package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.CsatWorkbookSourceConfig;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource.WorkbookFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bucket source against a stub that answers S3.
 *
 * <p>The stub is the point: the real bucket is a Supabase one whose credentials
 * are not in the repo, and the questions worth asking — does a listing become
 * the right four files, is a lock file skipped, does the reload button really
 * re-read, does a refused key say so without quoting the secret — are all
 * answered without a network. It signs and speaks S3 for real; only the server
 * on the other end is fake, which is also why it stands in for AWS as well as
 * for Supabase.
 */
class BucketCsatWorkbookSourceTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    /** Answers the listing, and hands out bytes for anything below the bucket. */
    private void serve(String listing, int listStatus, byte[] object) {
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean isListing = exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("list-type=2");
            byte[] body = isListing ? listing.getBytes(StandardCharsets.UTF_8) : object;
            int status = isListing ? listStatus : 200;
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String listing(String prefix, String... keys) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Name>csat-workbooks</Name><Prefix>" + prefix + "</Prefix>"
                + "<KeyCount>" + keys.length + "</KeyCount><MaxKeys>200</MaxKeys>"
                + "<IsTruncated>false</IsTruncated>");
        for (String key : keys) {
            xml.append("<Contents><Key>").append(key).append("</Key>")
                    .append("<LastModified>2026-09-09T03:00:00.000Z</LastModified>")
                    .append("<ETag>&quot;abc&quot;</ETag><Size>2048</Size>")
                    .append("<StorageClass>STANDARD</StorageClass></Contents>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    private static final String ERROR = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Error><Code>%s</Code><Message>%s</Message></Error>";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private KpiCsatProperties properties() {
        KpiCsatProperties properties = new KpiCsatProperties();
        KpiCsatProperties.Bucket bucket = properties.getBucket();
        bucket.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        bucket.setRegion("ap-southeast-1");
        bucket.setName("csat-workbooks");
        bucket.setAccessKey("AKIAEXAMPLE");
        bucket.setSecretKey("s3cr3t-not-in-any-message");
        return properties;
    }

    @Test
    void listsTheWorkbooksAndNothingElseInTheBucket() {
        serve(listing("", "Post-installation CSAT Survey.xlsx", "Post-MA CSAT Survey.xlsx",
                "~$Post-MA CSAT Survey.xlsx", "notes.pdf", "archive/"), 200, new byte[0]);

        List<WorkbookFile> files = new BucketCsatWorkbookSource(properties()).list();

        assertThat(files).extracting(WorkbookFile::name)
                .containsExactly("Post-installation CSAT Survey.xlsx", "Post-MA CSAT Survey.xlsx");
        assertThat(files.get(0).size()).isEqualTo(2048);
        assertThat(files.get(0).lastModified()).isEqualTo(Instant.parse("2026-09-09T03:00:00Z"));
        // The signature the service re-reads on: same three parts as a folder's.
        assertThat(files.get(0).fingerprint()).contains("Post-installation CSAT Survey.xlsx", "2048");
    }

    /** A prefix is a folder in the bucket; the file keeps its own name. */
    @Test
    void readsUnderAPrefixAndKeepsTheFileName() throws IOException {
        serve(listing("2026/", "2026/Post-MA CSAT Survey.xlsx"), 200, "workbook-bytes".getBytes(StandardCharsets.UTF_8));
        KpiCsatProperties properties = properties();
        properties.getBucket().setPrefix("/2026/");

        List<WorkbookFile> files = new BucketCsatWorkbookSource(properties).list();

        assertThat(files).singleElement().extracting(WorkbookFile::name).isEqualTo("Post-MA CSAT Survey.xlsx");
        assertThat(requests.get(0)).contains("prefix=2026%2F");
        try (InputStream in = files.get(0).open()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("workbook-bytes");
        }
        // Path style, because Supabase and MinIO answer nothing else, and signed.
        assertThat(requests.get(1)).contains("/csat-workbooks/2026/Post-MA%20CSAT%20Survey.xlsx");
        assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256");
    }

    /**
     * Every request for the page lists the source to decide whether to re-parse.
     * Over a bucket that is a round trip, so one listing stands for a minute —
     * and the console's reload button has to mean more than "look again at what
     * you looked at".
     */
    @Test
    void reusesOneListingUntilTheReloadButtonSaysOtherwise() {
        serve(listing("", "Post-MA CSAT Survey.xlsx"), 200, new byte[0]);
        BucketCsatWorkbookSource source = new BucketCsatWorkbookSource(properties());

        source.list();
        source.list();
        assertThat(requests).hasSize(1);

        source.refresh();
        source.list();
        assertThat(requests).hasSize(2);
    }

    @Test
    void aRefusedKeySaysSoWithoutQuotingTheSecret() {
        serve(ERROR.formatted("AccessDenied", "Access Denied"), 403, new byte[0]);

        assertThatThrownBy(() -> new BucketCsatWorkbookSource(properties()).list())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("refused the credentials")
                .hasMessageContaining("bucket csat-workbooks")
                .hasMessageNotContaining("s3cr3t-not-in-any-message");
    }

    @Test
    void aMissingBucketNamesTheBucketRatherThanTheStackTrace() {
        serve(ERROR.formatted("NoSuchBucket", "The specified bucket does not exist"), 404, new byte[0]);

        assertThatThrownBy(() -> new BucketCsatWorkbookSource(properties()).list())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("csat-workbooks");
    }

    /** An empty bucket is a thing to say, not a crash and not an empty dashboard. */
    @Test
    void anEmptyBucketIsExplained() {
        serve(listing(""), 200, new byte[0]);

        assertThatThrownBy(() -> new BucketCsatWorkbookSource(properties()).list())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No .xlsx workbooks in bucket csat-workbooks");
    }

    /** The rule the deployed console turns on: credentials win, the folder is the fallback. */
    @Test
    void theBucketIsPreferredWhereverItIsConfigured() {
        CsatWorkbookSourceConfig config = new CsatWorkbookSourceConfig();
        assertThat(config.csatWorkbookSource(properties())).isInstanceOf(BucketCsatWorkbookSource.class);

        KpiCsatProperties folderOnly = new KpiCsatProperties();
        folderOnly.setFolder("/tmp/csat");
        assertThat(config.csatWorkbookSource(folderOnly)).isInstanceOf(FolderCsatWorkbookSource.class);

        // Half-configured is not configured: a name with no keys reads the folder
        // rather than failing every CSAT request with a signing error.
        KpiCsatProperties nameOnly = new KpiCsatProperties();
        nameOnly.getBucket().setName("csat-workbooks");
        assertThat(config.csatWorkbookSource(nameOnly)).isInstanceOf(FolderCsatWorkbookSource.class);
    }
}
