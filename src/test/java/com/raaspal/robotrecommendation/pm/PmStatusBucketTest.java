package com.raaspal.robotrecommendation.pm;

import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two PM boards do not use the same status labels, so bucketing happens once
 * at sync time and the planner never matches on a label.
 */
class PmStatusBucketTest {

    @Test
    void mapsTheLabelsBothBoardsActuallyUse() {
        assertThat(PmStatusBucket.fromRaw("Done")).isEqualTo(PmStatusBucket.COMPLETED);
        assertThat(PmStatusBucket.fromRaw("Working on it")).isEqualTo(PmStatusBucket.IN_PROGRESS);
        assertThat(PmStatusBucket.fromRaw("Planning")).isEqualTo(PmStatusBucket.PLANNED);
        // Delivery-only labels. Both are work that is still owed.
        assertThat(PmStatusBucket.fromRaw("Waiting on approval")).isEqualTo(PmStatusBucket.PLANNED);
        assertThat(PmStatusBucket.fromRaw("On Hold")).isEqualTo(PmStatusBucket.PLANNED);
    }

    @Test
    void isNotFooledByCaseOrSurroundingSpace() {
        assertThat(PmStatusBucket.fromRaw("  done  ")).isEqualTo(PmStatusBucket.COMPLETED);
        assertThat(PmStatusBucket.fromRaw("DONE")).isEqualTo(PmStatusBucket.COMPLETED);
    }

    @Test
    void treatsAnEmptyStatusAsUnplanned() {
        assertThat(PmStatusBucket.fromRaw(null)).isEqualTo(PmStatusBucket.UNPLANNED);
        assertThat(PmStatusBucket.fromRaw("   ")).isEqualTo(PmStatusBucket.UNPLANNED);
    }

    /**
     * A label nobody anticipated is still work somebody scheduled. Filing it as
     * UNPLANNED would hide it in the bucket meaning "nobody has touched this".
     */
    @Test
    void treatsAnUnknownLabelAsPlannedRatherThanUnplanned() {
        assertThat(PmStatusBucket.fromRaw("Rescheduled by customer")).isEqualTo(PmStatusBucket.PLANNED);
    }
}
