package com.raaspal.robotrecommendation.inventory.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves a stored {@code data:} URI as a real image response.
 *
 * <p>Photos live in the row as base64, which was forced by Render's ephemeral disk.
 * That is fine as storage and ruinous as delivery: a list of 92 robots carried 3.8 MB
 * of inlined photographs, and the warehouse's ~320 parts would have carried far more.
 * Lists now return a {@code hasImage} flag and point the browser here instead, so the
 * bytes travel once per image, in parallel, and stay in the browser cache.
 *
 * <p>Returning real bytes rather than the data URI is the whole point — a data URI in
 * JSON cannot be cached, cannot be lazy-loaded, and blocks the page until it arrives.
 */
final class StoredImage {

    /** {@code data:image/jpeg;base64,/9j/4AAQ...} — type and payload captured separately. */
    private static final Pattern DATA_URI = Pattern.compile("^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$", Pattern.DOTALL);

    /**
     * Images are immutable for a given URL: replacing a photo writes a new row value
     * and the browser asks again only because the page told it to. A year is the
     * conventional "as long as you like" for content addressed this way.
     */
    private static final Duration CACHE_FOR = Duration.ofDays(365);

    private StoredImage() {
    }

    /**
     * @param stored the {@code image_url} column value
     * @param what   named in the 404 when there is no photo — "InventoryItem image"
     */
    static ResponseEntity<Resource> serve(String stored, String what, Object id) {
        if (stored == null || stored.isBlank()) {
            throw new ResourceNotFoundException(what, "id", id);
        }

        // An http(s) URL is someone else's to serve; send the caller there rather
        // than proxying bytes we do not hold.
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            return ResponseEntity.status(302).header("Location", stored).build();
        }

        Matcher matcher = DATA_URI.matcher(stored.trim());
        if (!matcher.matches()) {
            throw new ResourceNotFoundException(what, "id", id);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            // A corrupt payload is a missing image as far as the caller is concerned;
            // it must not become a 500 on a page that merely wanted a thumbnail.
            throw new ResourceNotFoundException(what, "id", id);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(matcher.group(1)))
                .contentLength(bytes.length)
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePrivate())
                .body(new ByteArrayResource(bytes));
    }
}
