package com.raaspal.robotrecommendation.pm.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The filters both views share. Every field is optional; null means "do not
 * narrow by this", which is what the repository queries test for.
 *
 * <p>Companies are the exception, and they travel as whichever list is shorter. The
 * filter opens with every chain ticked, so "hide a few" is a short exclusion list -
 * sending the selection instead would put 177 names in the query string to express
 * "no filter at all". But "show only PCS" is the same thing inverted: 148 exclusions,
 * 8 KB of URL, and a request Tomcat and nginx both refuse. So the console sends
 * {@code company=} (the ticked chains) when that is the shorter list and
 * {@code excludeCompany=} otherwise, and an include list, when present, wins.
 */
public record PmFilter(String serviceLine, String region, String zone, String province, String status,
                       String owner, String q, Set<String> excludedCompanies, Set<String> includedCompanies) {

    /** Turns blank query-string values into the nulls the queries expect. */
    public static PmFilter of(String serviceLine, String region, String zone, String province, String status,
                              String owner, String q, List<String> excludedCompanies) {
        return of(serviceLine, region, zone, province, status, owner, q, excludedCompanies, null);
    }

    public static PmFilter of(String serviceLine, String region, String zone, String province, String status,
                              String owner, String q, List<String> excludedCompanies,
                              List<String> includedCompanies) {
        return new PmFilter(blankToNull(serviceLine), blankToNull(region), blankToNull(zone),
                blankToNull(province), blankToNull(status), blankToNull(owner), blankToNull(q),
                names(excludedCompanies), names(includedCompanies));
    }

    /**
     * Whether this company should be hidden. With an include list, everything not on
     * it - a row with no company at all included; the reader asked for named chains.
     */
    public boolean excludes(String company) {
        if (includedCompanies != null && !includedCompanies.isEmpty()) {
            return company == null || !includedCompanies.contains(company);
        }
        return company != null && excludedCompanies != null && excludedCompanies.contains(company);
    }

    /** True when either list narrows the sheet. */
    public boolean filtersCompanies() {
        return (includedCompanies != null && !includedCompanies.isEmpty())
                || (excludedCompanies != null && !excludedCompanies.isEmpty());
    }

    private static Set<String> names(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(out::add);
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
