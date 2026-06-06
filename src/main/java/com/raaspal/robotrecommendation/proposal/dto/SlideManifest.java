package com.raaspal.robotrecommendation.proposal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlideManifest(List<SlideData> slides) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlideData(
            String type,           // title | key_stats | content | table | closing
            String title,
            String subtitle,       // title slide only
            List<String> bullets,  // content / closing
            List<StatItem> stats,  // key_stats
            List<String> headers,  // table
            List<List<String>> rows // table
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatItem(String label, String value) {}
}
