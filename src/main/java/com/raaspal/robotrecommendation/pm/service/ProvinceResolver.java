package com.raaspal.robotrecommendation.pm.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns whatever somebody typed in monday's province cell into a canonical
 * province, region and zone.
 *
 * <p>Why this exists rather than reading monday's own region column: that column
 * holds 12 different spellings for roughly 7 regions (ใต้ and ภาคใต้ are the same
 * place; so are อีสาน and ตะวันออกเฉียงเหนือ), and grouping a planner's grid by it
 * would split one region into several rows. Province is both cleaner and finer,
 * so region and zone are derived from it.
 *
 * <p>What this deliberately does NOT do is guess. An unrecognised or empty
 * province resolves to {@link #UNASSIGNED}, which the planner shows as its own
 * bucket with a visible count. Roughly half of the Delivery contracts have no
 * province at all, and silently scattering them into plausible-looking regions
 * would make the grid confidently wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvinceResolver {

    /** Stand-in for "we do not know", used for province, region and zone alike. */
    public static final String UNASSIGNED = "UNASSIGNED";

    private static final String RESOURCE = "pm/province-master.json";

    /** Prefixes staff type before the name; stripped before lookup. */
    private static final List<String> PREFIXES = List.of("จังหวัด", "จ.", "จ ", "province of ", "province ");

    private final ObjectMapper objectMapper;

    /** Lookup key (normalised province name or alias) -> resolved geography. */
    private final Map<String, ResolvedProvince> index = new HashMap<>();

    private List<String> regions = List.of();

    /** One province and the region/zone it belongs to. */
    public record ResolvedProvince(String province, String provinceEn, String region, String zone) {

        static ResolvedProvince unassigned() {
            return new ResolvedProvince(UNASSIGNED, UNASSIGNED, UNASSIGNED, UNASSIGNED);
        }
    }

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            ProvinceMasterDocument doc = objectMapper.readValue(in, ProvinceMasterDocument.class);
            this.regions = doc.regions() == null ? List.of() : doc.regions();

            for (ProvinceEntry entry : doc.provinces()) {
                ResolvedProvince resolved = new ResolvedProvince(
                        entry.provinceTh(), entry.provinceEn(), entry.region(), entry.zone());
                put(entry.provinceTh(), resolved);
                put(entry.provinceEn(), resolved);
                if (entry.aliases() != null) {
                    entry.aliases().forEach(alias -> put(alias, resolved));
                }
            }
            log.info("Loaded {} provinces ({} lookup keys) across {} regions from {}",
                    doc.provinces().size(), index.size(), regions.size(), RESOURCE);
        } catch (Exception e) {
            // Fail fast: a planner grouped by a half-loaded province table is worse
            // than one that refuses to start, because nothing about it looks wrong.
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    private void put(String key, ResolvedProvince value) {
        if (key != null && !key.isBlank()) {
            index.put(normalise(key), value);
        }
    }

    /** The canonical region list, in planning order. */
    public List<String> regions() {
        return regions;
    }

    /** Every zone, grouped under its region, in planning order. */
    public List<String> zones() {
        return index.values().stream()
                .map(ResolvedProvince::zone)
                .distinct()
                .sorted()
                .toList();
    }

    /** Every canonical province name, Thai. */
    public List<String> provinces() {
        List<String> all = new ArrayList<>(index.values().stream().map(ResolvedProvince::province).distinct().toList());
        all.sort(String::compareTo);
        return all;
    }

    /**
     * Resolves a raw province cell. Never returns null - an unknown value comes
     * back as {@link #UNASSIGNED} so it stays countable.
     */
    public ResolvedProvince resolve(String rawProvince) {
        if (rawProvince == null || rawProvince.isBlank()) {
            return ResolvedProvince.unassigned();
        }
        ResolvedProvince hit = index.get(normalise(rawProvince));
        if (hit != null) {
            return hit;
        }
        // Last resort: a cell like "อ.เมือง จ.เชียงใหม่" or "Chiang Mai 50000" still
        // contains a province name, so look for one inside the text before giving up.
        String needle = normalise(rawProvince);
        for (Map.Entry<String, ResolvedProvince> entry : index.entrySet()) {
            if (entry.getKey().length() >= 4 && needle.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return ResolvedProvince.unassigned();
    }

    /** Lowercases, strips prefixes, and removes spaces and punctuation. */
    private static String normalise(String value) {
        String out = value.trim().toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (out.startsWith(prefix)) {
                out = out.substring(prefix.length());
                break;
            }
        }
        return out.replaceAll("[\\s\\p{Punct}]+", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProvinceMasterDocument(List<String> regions, List<ProvinceEntry> provinces) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProvinceEntry(String provinceTh, String provinceEn, String region, String zone,
                                 List<String> aliases) {
    }
}
