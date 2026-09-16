package com.raaspal.robotrecommendation.pm.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The filters both views share. Every field is optional; null means "do not
 * narrow by this", which is what the repository queries test for.
 *
 * <p>Companies are the exception, and they are carried as an exclusion set rather
 * than a selection. The filter opens with every chain ticked, so the interesting
 * state is always the short list somebody unticked - sending the selection instead
 * would put 177 names in the query string to express "no filter at all".
 */
public record PmFilter(String serviceLine, String region, String zone, String province, String status,
                       String owner, String q, Set<String> excludedCompanies) {

    /** Turns blank query-string values into the nulls the queries expect. */
    public static PmFilter of(String serviceLine, String region, String zone, String province, String status,
                              String owner, String q, List<String> excludedCompanies) {
        Set<String> excluded = new LinkedHashSet<>();
        if (excludedCompanies != null) {
            excludedCompanies.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(excluded::add);
        }
        return new PmFilter(blankToNull(serviceLine), blankToNull(region), blankToNull(zone),
                blankToNull(province), blankToNull(status), blankToNull(owner), blankToNull(q), excluded);
    }

    /** Whether this company should be hidden. */
    public boolean excludes(String company) {
        return company != null && excludedCompanies != null && excludedCompanies.contains(company);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
