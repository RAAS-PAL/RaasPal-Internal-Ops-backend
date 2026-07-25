package com.raaspal.robotrecommendation.partner.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Assign many deployments to one partner in a single call — used by the
 * searchable multi-select "assign robots" box for partners with dozens of
 * robots. Unknown ids are silently skipped; the response carries how many were
 * actually assigned.
 */
public record BulkAssignRequest(
        @NotEmpty(message = "At least one deployment is required")
        List<UUID> deploymentIds) {
}
