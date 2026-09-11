package com.raaspal.robotrecommendation.pm.dto;

import java.util.List;

/**
 * What the filter bar offers.
 *
 * <p>Regions and zones come from the province master, not from what happens to be
 * in the data, so the list stays stable and a region with no PM this year is still
 * selectable rather than quietly disappearing. Companies are the opposite - they
 * are derived from the data, so they are sent with their site counts, biggest
 * first, because size is what tells a planner which chain is worth excluding.
 */
public record PmFilterOptions(List<String> serviceLines, List<String> regions, List<String> zones,
                              List<String> provinces, List<String> statuses, List<String> owners,
                              List<Company> companies) {

    public record Company(String name, long siteCount) {
    }
}
