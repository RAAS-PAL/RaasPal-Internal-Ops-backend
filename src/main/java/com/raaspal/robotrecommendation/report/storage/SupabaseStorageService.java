package com.raaspal.robotrecommendation.report.storage;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Uploads generated report files to a Supabase Storage bucket and returns a
 * time-limited signed download URL for each one. The signed URL is what the
 * LINE Flex message (built downstream in n8n) links to, since LINE cannot
 * attach files. Inert until {@code app.supabase.*} is configured.
 */
@Slf4j
@Service
public class SupabaseStorageService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final RestClient restClient;
    private final String supabaseUrl;
    private final String serviceKey;
    private final String bucket;
    private final int signedUrlExpirySeconds;

    public SupabaseStorageService(
            @Value("${app.supabase.url:}") String supabaseUrl,
            @Value("${app.supabase.service-key:}") String serviceKey,
            @Value("${app.supabase.storage-bucket:monthly-reports}") String bucket,
            @Value("${app.supabase.signed-url-expiry-seconds:2592000}") int signedUrlExpirySeconds) {
        this.supabaseUrl = stripTrailingSlash(supabaseUrl);
        this.serviceKey = serviceKey;
        this.bucket = bucket;
        this.signedUrlExpirySeconds = signedUrlExpirySeconds;
        this.restClient = RestClient.builder().baseUrl(this.supabaseUrl).build();
    }

    /** Whether the Supabase Storage credentials needed to upload are configured. */
    public boolean isConfigured() {
        return !supabaseUrl.isBlank() && !serviceKey.isBlank();
    }

    /**
     * Uploads {@code xlsx} to {@code objectPath} (upserting) and returns an
     * absolute signed download URL valid for {@code signedUrlExpirySeconds}.
     *
     * @param objectPath path within the bucket, e.g. {@code "<customerId>/2026-05/<serial>.xlsx"}
     */
    public String uploadAndSign(String objectPath, byte[] xlsx) {
        upload(objectPath, xlsx);
        return createSignedUrl(objectPath);
    }

    private void upload(String objectPath, byte[] xlsx) {
        try {
            restClient.post()
                    .uri("/storage/v1/object/{bucket}/{path}", bucket, objectPath)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .header("x-upsert", "true")
                    .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                    .body(xlsx)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new SupabaseStorageException(
                    "Failed to upload " + objectPath + ": " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new SupabaseStorageException("Failed to upload " + objectPath + ": " + e.getMessage(), e);
        }
    }

    private String createSignedUrl(String objectPath) {
        try {
            JsonNode response = restClient.post()
                    .uri("/storage/v1/object/sign/{bucket}/{path}", bucket, objectPath)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", signedUrlExpirySeconds))
                    .retrieve()
                    .body(JsonNode.class);

            String signedPath = response == null ? null : response.path("signedURL").asText(null);
            if (signedPath == null || signedPath.isBlank()) {
                throw new SupabaseStorageException("Sign response missing signedURL for " + objectPath);
            }
            // signedURL is relative to /storage/v1, e.g. "/object/sign/<bucket>/<path>?token=..."
            return supabaseUrl + "/storage/v1" + signedPath;
        } catch (RestClientResponseException e) {
            throw new SupabaseStorageException(
                    "Failed to sign " + objectPath + ": " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (SupabaseStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new SupabaseStorageException("Failed to sign " + objectPath + ": " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
