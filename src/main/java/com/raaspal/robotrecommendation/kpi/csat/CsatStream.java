package com.raaspal.robotrecommendation.kpi.csat;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The four surveys the RE team runs, one workbook each. The deck breaks CSAT
 * down exactly this way, and the four pool into the overall figure.
 */
public enum CsatStream {
    /** After an installation — the smallest survey by far (a handful a month). */
    INSTALLATION("installation"),
    /** After a preventive-maintenance visit. The workbook calls it "MA". */
    PM("pm"),
    /** After a repair call on a cleaning robot. */
    CM_CLEANING("cleaning"),
    /** After a repair call on a delivery robot. */
    CM_DELIVERY("delivery");

    /** Standalone "MA" — "Post-MA", "Survey MA as of" — but not the "ma" inside other words. */
    private static final Pattern MA = Pattern.compile("(^|[^a-z])ma([^a-z]|$)");

    private final String key;

    CsatStream(String key) {
        this.key = key;
    }

    /** The field name the API uses for this stream. */
    public String key() {
        return key;
    }

    /**
     * Which survey a workbook is, from its sheet title ("Post-installation CSAT
     * Survey", "Post-MA CSAT Survey", "Post-Mantaianace Cleaning bot CSAT
     * Survey" — spelling as found) or, failing that, its file name. Cleaning and
     * delivery are tested first because their titles also contain "MA"-like
     * fragments; "MA" itself is matched only as a whole word.
     */
    public static Optional<CsatStream> detect(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("cleaning")) {
            return Optional.of(CM_CLEANING);
        }
        if (t.contains("delivery")) {
            return Optional.of(CM_DELIVERY);
        }
        if (t.contains("install")) {
            return Optional.of(INSTALLATION);
        }
        if (MA.matcher(t).find()) {
            return Optional.of(PM);
        }
        return Optional.empty();
    }
}
