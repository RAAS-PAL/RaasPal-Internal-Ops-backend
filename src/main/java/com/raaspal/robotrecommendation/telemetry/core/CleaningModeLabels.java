package com.raaspal.robotrecommendation.telemetry.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a raw cleaning-mode code into a friendly English label.
 *
 * <p>Gausium reports modes in mixed forms — English codes ({@code scrub}),
 * Chinese terms ({@code 洗地}), and occasionally an underscore-prefixed variant
 * ({@code __尘推}). Anything customer- or partner-facing needs one consistent
 * English rendering.
 *
 * <p><strong>Translation happens on the way out, never at storage.</strong> The
 * database keeps exactly what the manufacturer sent, so a wrong or missing
 * mapping is fixed here and every response is instantly correct — no re-sync of
 * historical rows, and the stored data still reconciles against Gausium's own
 * portal, which displays the Chinese.
 *
 * <p>This is the single source of truth: the monthly customer reports and the
 * partner API both call it, so a customer and their service partner never see
 * the same task described two different ways.
 */
@Slf4j
public final class CleaningModeLabels {

    private CleaningModeLabels() {
    }

    /** Unmapped modes are logged once each, so a new one is noticed rather than silently shown as "Other". */
    private static final Set<String> REPORTED_UNKNOWN = ConcurrentHashMap.newKeySet();

    private static final Map<String, String> MODE_LABELS = Map.ofEntries(
            Map.entry("mop", "Mopping"),
            Map.entry("mop_wet", "Wet Mopping"),
            Map.entry("sweep", "Sweeping"),
            Map.entry("vacuum", "Vacuuming"),
            Map.entry("scrub", "Scrubbing"),
            Map.entry("sweep_vacuum", "Sweep & Vacuum"),
            Map.entry("洗地", "Floor Washing"),
            Map.entry("尘推", "Dust Push"),
            Map.entry("推尘", "Dust Push"),
            Map.entry("扫地", "Sweeping"),
            Map.entry("清扫", "Sweeping"),
            Map.entry("吸尘", "Vacuuming"),
            Map.entry("拖地", "Mopping"),
            Map.entry("洗扫", "Wash & Sweep"),
            Map.entry("轻度清洁", "Light Cleaning"),
            Map.entry("中度清洁", "Medium Cleaning"),
            Map.entry("重度清洁", "Deep Cleaning"));

    /**
     * The English label for a raw mode, or {@code null} for a null/blank input.
     * An unrecognised non-English mode becomes {@code "Other"} rather than being
     * passed through, so no Chinese ever reaches a reader who cannot use it.
     */
    public static String toEnglish(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return null;
        }
        String key = rawMode.trim().toLowerCase().replaceFirst("^_+", ""); // drop any leading "__"
        String mapped = MODE_LABELS.get(key);
        if (mapped != null) {
            return mapped;
        }
        if (!key.chars().allMatch(c -> c < 128)) {
            // A mode we have never seen. Log it once so it can be added to the map
            // instead of quietly reading as "Other" forever.
            if (REPORTED_UNKNOWN.add(key)) {
                log.warn("Unmapped cleaning mode '{}' — add it to CleaningModeLabels", rawMode);
            }
            return "Other";
        }
        // An English code we do not have an entry for: tidy it up rather than
        // discard it ("sweep_vacuum" → "Sweep vacuum").
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
