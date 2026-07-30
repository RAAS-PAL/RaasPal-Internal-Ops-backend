package com.raaspal.robotrecommendation.telemetry.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gausium reports cleaning modes in Chinese, which is meaningless to a Thai
 * partner or customer. These pin the translation used by both the monthly
 * reports and the partner API.
 */
class CleaningModeLabelsTest {

    /** The two modes that actually occur throughout the production data. */
    @Test
    void translatesTheModesSeenInProduction() {
        assertThat(CleaningModeLabels.toEnglish("洗地")).isEqualTo("Floor Washing");
        assertThat(CleaningModeLabels.toEnglish("尘推")).isEqualTo("Dust Push");
    }

    /** Gausium sometimes prefixes the mode, e.g. "__尘推" in their own sample payload. */
    @Test
    void stripsTheUnderscorePrefixGausiumSometimesAdds() {
        assertThat(CleaningModeLabels.toEnglish("__尘推")).isEqualTo("Dust Push");
        assertThat(CleaningModeLabels.toEnglish("_洗地")).isEqualTo("Floor Washing");
    }

    @Test
    void translatesEnglishCodesToo() {
        assertThat(CleaningModeLabels.toEnglish("scrub")).isEqualTo("Scrubbing");
        assertThat(CleaningModeLabels.toEnglish("sweep_vacuum")).isEqualTo("Sweep & Vacuum");
    }

    /**
     * An unrecognised Chinese mode must never reach a reader who cannot use it —
     * passing it through would defeat the point of translating at all.
     */
    @Test
    void unmappedChineseBecomesOtherRatherThanLeakingThrough() {
        assertThat(CleaningModeLabels.toEnglish("擦窗户")).isEqualTo("Other");
    }

    /** An unknown English code is readable as-is, so it is tidied rather than hidden. */
    @Test
    void unmappedEnglishCodeIsTidiedNotDiscarded() {
        assertThat(CleaningModeLabels.toEnglish("deep_scrub")).isEqualTo("Deep scrub");
    }

    @Test
    void handlesWhitespaceAndCasing() {
        assertThat(CleaningModeLabels.toEnglish("  SCRUB  ")).isEqualTo("Scrubbing");
    }

    @Test
    void nullAndBlankStayNull() {
        assertThat(CleaningModeLabels.toEnglish(null)).isNull();
        assertThat(CleaningModeLabels.toEnglish("   ")).isNull();
    }
}
