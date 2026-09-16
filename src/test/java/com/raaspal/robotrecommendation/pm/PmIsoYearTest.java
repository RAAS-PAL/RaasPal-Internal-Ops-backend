package com.raaspal.robotrecommendation.pm;

import com.raaspal.robotrecommendation.pm.service.PmPlanningService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grid is "52-week" in name only. Some years have 53 ISO weeks, and a grid
 * hard-coded to 52 columns drops the last one silently - which in 2026 is the week
 * of 28 December.
 */
class PmIsoYearTest {

    @Test
    void countsFiftyThreeWeeksInTheYearsThatHaveThem() {
        // 1 January 2026 is a Thursday, which is exactly the condition for 53 weeks.
        assertThat(PmPlanningService.weeksInIsoYear(2026)).isEqualTo(53);
        assertThat(PmPlanningService.weeksInIsoYear(2020)).isEqualTo(53);
    }

    @Test
    void countsFiftyTwoOtherwise() {
        assertThat(PmPlanningService.weeksInIsoYear(2025)).isEqualTo(52);
        assertThat(PmPlanningService.weeksInIsoYear(2027)).isEqualTo(52);
    }
}
