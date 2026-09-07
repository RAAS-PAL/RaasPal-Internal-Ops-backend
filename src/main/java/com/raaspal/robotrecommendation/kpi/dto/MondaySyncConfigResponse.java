package com.raaspal.robotrecommendation.kpi.dto;

import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;

import java.util.List;

/**
 * The effective sync configuration, for the console's settings panel: whether
 * a token is present (never the token itself), whether the nightly run is on,
 * and the column mapping per board so it can be checked against
 * {@code GET /api/v1/kpi/monday/boards/{id}}.
 */
public record MondaySyncConfigResponse(
        boolean tokenConfigured,
        boolean schedulerEnabled,
        String syncCron,
        String syncZone,
        int repeatWindowDays,
        List<KpiMondayProperties.Board> boards
) {
}
