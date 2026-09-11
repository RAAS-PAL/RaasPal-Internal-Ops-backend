package com.raaspal.robotrecommendation.kpi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the CSAT survey workbooks live. See the {@code app.kpi.csat.*} block in
 * {@code application.properties}.
 *
 * <p>CSAT is the one RE KPI with no monday source: it comes from the post-job
 * phone survey, which the RE team tallies by hand into four workbooks (one per
 * survey) once a month. So this KPI is deliberately not live — it changes when
 * the workbooks are replaced, and the console says so.
 *
 * <p>Two places they can be replaced: a {@link #bucket} when one is configured,
 * a {@link #folder} otherwise. The bucket wins, and is what the deployed
 * console uses; the folder is for a developer's machine.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kpi.csat")
public class KpiCsatProperties {

    /** Folder holding the workbooks. Blank means CSAT reads the bucket, or is not configured. */
    private String folder;

    /** The object-storage bucket holding the same four workbooks. */
    private Bucket bucket = new Bucket();

    /** Zone for reporting file timestamps; the team is in Bangkok. */
    private String zone = "Asia/Bangkok";

    /**
     * An S3 bucket the RE team uploads to.
     *
     * <p>The S3 API rather than Supabase's own: Supabase Storage answers S3
     * through a gateway, so the same settings describe a Supabase bucket and an
     * AWS one. Supabase wants {@code endpoint} pointed at
     * {@code https://<project>.supabase.co/storage/v1/s3}, its region, and a
     * pair of S3 access keys from the dashboard; AWS wants the region and an
     * IAM key, and no endpoint at all.
     */
    @Getter
    @Setter
    public static class Bucket {

        /** S3 endpoint. Blank = AWS's own for the region. */
        private String endpoint;

        /** The bucket's region — for Supabase, the project's. */
        private String region = "ap-southeast-1";

        /** Bucket name. Keep it private: the workbooks name customers. */
        private String name;

        /** Optional folder inside the bucket, e.g. {@code 2026}. */
        private String prefix = "";

        /** Access key id. Supabase: Storage settings, S3 access keys. */
        private String accessKey;

        /** Secret. Never logged, never returned by the API, never in the browser. */
        private String secretKey;

        /**
         * Address objects as {@code endpoint/bucket/key} rather than
         * {@code bucket.endpoint/key}. Supabase and MinIO need it; AWS accepts it.
         */
        private boolean pathStyle = true;

        /** How long one listing is reused; the reload button ignores it. */
        private int listCacheSeconds = 60;

        private int connectTimeoutSeconds = 10;

        private int readTimeoutSeconds = 60;

        /** Enough to read a bucket: a name and a key pair. */
        public boolean isConfigured() {
            return isSet(name) && isSet(accessKey) && isSet(secretKey);
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }
}
