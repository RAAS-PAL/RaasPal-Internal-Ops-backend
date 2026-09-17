package com.raaspal.robotrecommendation.pm;

import com.raaspal.robotrecommendation.pm.dto.PmFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The company filter as either list. "Hide a few" is an exclusion; "show only PCS" is
 * an inclusion, because the exclusion form of that was 148 names and 8 KB of URL.
 */
class PmFilterTest {

    @Test
    void anExclusionListHidesJustThoseChains() {
        PmFilter filter = PmFilter.of(null, null, null, null, null, null, null, List.of("BBQ", " MK "));

        assertThat(filter.filtersCompanies()).isTrue();
        assertThat(filter.excludes("BBQ")).isTrue();
        assertThat(filter.excludes("MK")).isTrue();
        assertThat(filter.excludes("PCS")).isFalse();
        // A row with no chain is not one of the hidden ones.
        assertThat(filter.excludes(null)).isFalse();
    }

    @Test
    void anInclusionListShowsJustThoseChainsAndWinsOverExclusion() {
        PmFilter filter = PmFilter.of(null, null, null, null, null, null, null,
                List.of("PCS"), List.of("PCS"));

        assertThat(filter.filtersCompanies()).isTrue();
        assertThat(filter.excludes("PCS")).isFalse();
        assertThat(filter.excludes("BBQ")).isTrue();
        // The reader asked for named chains; a row with none is not one of them.
        assertThat(filter.excludes(null)).isTrue();
    }

    @Test
    void noListMeansNoCompanyFilter() {
        PmFilter filter = PmFilter.of(null, null, null, null, null, null, null, List.of(" ", ""), null);

        assertThat(filter.filtersCompanies()).isFalse();
        assertThat(filter.excludes("PCS")).isFalse();
    }
}
