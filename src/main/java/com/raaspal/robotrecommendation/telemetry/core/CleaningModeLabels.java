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

    /**
     * Every value observed across the production fleet, plus defensive entries.
     *
     * <p><strong>The English wording is Gausium's own,</strong> supplied by them on
     * 2026-07-30 in answer to a list of every distinct value in our database. It is
     * therefore authoritative about meaning and should not be "improved" locally —
     * where their term differs from the obvious literal reading, theirs is the one
     * PCS and Gausium's own staff will recognise. Two of their answers overturned
     * reasonable guesses: {@code 洗地} is <em>Scrubbing</em> rather than "floor
     * washing", and it is a distinct activity from {@code 清洗} (<em>Washing</em>),
     * which we had been close to merging.
     *
     * <p>Chinese and English forms of the same activity deliberately share one
     * label. Different firmware reports the same mode either way — Gausium confirmed
     * {@code 尘推} and {@code dust mop} are one activity — and letting them render
     * differently would split a partner's totals across two buckets for no reason.
     */
    private static final Map<String, String> MODE_LABELS = Map.ofEntries(
            // ── Scrubbing (洗地 == "scrub") ──
            Map.entry("洗地", "Scrubbing"),
            Map.entry("scrub", "Scrubbing"),
            Map.entry("清洗", "Washing"),
            Map.entry("洗扫", "Scrub & Sweep"),

            // ── Dust mopping (尘推 / 推尘 == "dust mop") ──
            Map.entry("尘推", "Dust Mopping"),
            Map.entry("推尘", "Dust Mopping"),
            Map.entry("dust_mop", "Dust Mopping"),

            // ── Mopping ──
            Map.entry("拖地", "Mopping"),
            Map.entry("mop", "Mopping"),
            Map.entry("mop_wet", "Wet Mopping"),

            // ── Sweeping ──
            Map.entry("扫地", "Sweeping"),
            Map.entry("清扫", "Sweeping"),
            Map.entry("sweep", "Sweeping"),
            Map.entry("sweep_vacuum", "Sweep & Vacuum"),

            // ── Vacuuming / water pickup ──
            Map.entry("吸尘", "Vacuuming"),
            Map.entry("vacuum", "Vacuuming"),
            Map.entry("吸水", "Water Sucking"),

            // ── Intensity tiers (Chinese and English forms must agree) ──
            Map.entry("轻度清洁", "Light Cleaning"),
            Map.entry("light_cleaning", "Light Cleaning"),
            Map.entry("中度清洁", "Medium-Duty Cleaning"),
            Map.entry("middle_cleaning", "Medium-Duty Cleaning"),
            Map.entry("重度清洁", "Heavy-Duty Cleaning"),
            Map.entry("heavy_cleaning", "Heavy-Duty Cleaning"),

            // ── Not cleaning at all: the robot is inspecting a route ──
            Map.entry("巡检", "Patrol Inspection"),
            Map.entry("patrol", "Patrol Inspection"));

    /**
     * The English label for a raw mode, or {@code null} for a null/blank input.
     * An unrecognised non-English mode becomes {@code "Other"} rather than being
     * passed through, so no Chinese ever reaches a reader who cannot use it.
     */
    public static String toEnglish(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return null;
        }
        String key = normalise(rawMode);
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
        // discard it ("deep_scrub" → "Deep scrub"). Still worth flagging, since a
        // new code should get a proper label.
        if (REPORTED_UNKNOWN.add(key)) {
            log.warn("Unmapped cleaning mode '{}' — add it to CleaningModeLabels", rawMode);
        }
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /**
     * Reduces the many shapes Gausium sends to one lookup key.
     *
     * <p>Observed in production: a bare code ({@code vacuum}), an underscore
     * prefix ({@code __尘推}), a <em>numeric</em> prefix ({@code 2_vacuum},
     * {@code 1_dust mop} — presumably a step number within a plan), and spaces
     * where other robots use underscores ({@code dust mop} vs {@code dust_mop}).
     * All four must resolve identically.
     */
    private static String normalise(String rawMode) {
        return rawMode.trim().toLowerCase()
                .replaceFirst("^\\d*_+", "")   // "2_vacuum" → "vacuum", "__尘推" → "尘推"
                .trim()
                .replaceAll("\\s+", "_");      // "dust mop" → "dust_mop"
    }
}
