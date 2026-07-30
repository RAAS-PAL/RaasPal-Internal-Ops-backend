package com.raaspal.robotrecommendation.telemetry.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gausium reports cleaning modes in Chinese, which is meaningless to a Thai
 * partner or customer. These pin the translation used by both the monthly
 * reports and the partner API.
 */
class CleaningModeLabelsTest {

    /**
     * Every distinct value present in the production database, queried across
     * ~34,000 task reports. None may fall through to "Other" — that is the whole
     * point of the map, and a gap here is invisible in the data until a partner
     * asks why a mode reads as "Other".
     */
    @Test
    void everyModeInProductionResolvesToARealLabel() {
        Map<String, String> observed = new LinkedHashMap<>();
        observed.put("洗地", "Floor Washing");          // 20,762 tasks
        observed.put("尘推", "Dust Push");              //  8,508
        observed.put("清扫", "Sweeping");               //  2,219
        observed.put("吸尘", "Vacuuming");              //    726
        observed.put("清洗", "Washing");                //    466
        observed.put("dust mop", "Dust Push");         //    369
        observed.put("mop", "Mopping");                //    200
        observed.put("重度清洁", "Deep Cleaning");       //    162
        observed.put("vacuum", "Vacuuming");           //    159
        observed.put("轻度清洁", "Light Cleaning");      //    158
        observed.put("中度清洁", "Medium Cleaning");     //    149
        observed.put("patrol", "Patrol");              //     95
        observed.put("mop_wet", "Wet Mopping");        //     87
        observed.put("巡检", "Patrol");                 //     48
        observed.put("scrub", "Scrubbing");            //     43
        observed.put("middle_cleaning", "Medium Cleaning"); // 21
        observed.put("吸水", "Water Suction");          //      7
        observed.put("2_vacuum", "Vacuuming");         //      1
        observed.put("light_cleaning", "Light Cleaning"); //   1
        observed.put("1_dust mop", "Dust Push");       //      1

        observed.forEach((raw, expected) ->
                assertThat(CleaningModeLabels.toEnglish(raw))
                        .as("mode '%s'", raw)
                        .isEqualTo(expected));

        assertThat(observed.values()).as("no production mode may read as Other")
                .doesNotContain("Other");
    }

    /**
     * The same activity must carry one label however the firmware spells it,
     * otherwise a partner aggregating by mode gets two buckets for one thing.
     */
    @Test
    void chineseAndEnglishFormsOfOneActivityAgree() {
        assertThat(CleaningModeLabels.toEnglish("dust mop"))
                .isEqualTo(CleaningModeLabels.toEnglish("尘推"));
        assertThat(CleaningModeLabels.toEnglish("middle_cleaning"))
                .isEqualTo(CleaningModeLabels.toEnglish("中度清洁"));
        assertThat(CleaningModeLabels.toEnglish("light_cleaning"))
                .isEqualTo(CleaningModeLabels.toEnglish("轻度清洁"));
        assertThat(CleaningModeLabels.toEnglish("patrol"))
                .isEqualTo(CleaningModeLabels.toEnglish("巡检"));
        assertThat(CleaningModeLabels.toEnglish("vacuum"))
                .isEqualTo(CleaningModeLabels.toEnglish("吸尘"));
    }

    /**
     * Gausium prefixes modes two ways: underscores ("__尘推", in their own sample)
     * and a step number ("2_vacuum", "1_dust mop", seen in production).
     */
    @Test
    void stripsBothUnderscoreAndNumericPrefixes() {
        assertThat(CleaningModeLabels.toEnglish("__尘推")).isEqualTo("Dust Push");
        assertThat(CleaningModeLabels.toEnglish("_洗地")).isEqualTo("Floor Washing");
        assertThat(CleaningModeLabels.toEnglish("2_vacuum")).isEqualTo("Vacuuming");
        assertThat(CleaningModeLabels.toEnglish("1_dust mop")).isEqualTo("Dust Push");
        assertThat(CleaningModeLabels.toEnglish("12_洗地")).isEqualTo("Floor Washing");
    }

    /** Spaces and underscores are interchangeable in the codes Gausium sends. */
    @Test
    void spacesAndUnderscoresAreEquivalent() {
        assertThat(CleaningModeLabels.toEnglish("dust mop"))
                .isEqualTo(CleaningModeLabels.toEnglish("dust_mop"));
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
