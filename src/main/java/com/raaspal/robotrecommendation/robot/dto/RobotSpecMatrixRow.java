package com.raaspal.robotrecommendation.robot.dto;

import java.util.Map;
import java.util.UUID;

/**
 * One robot model plus its cleaning specifications, for the side-by-side spec
 * matrix in the console.
 *
 * <p><strong>Why {@code specs} is an untyped map.</strong> {@code robot_specs_cleaning}
 * has 101 columns, and a JPA entity mirroring it would be ~400 lines that must be
 * edited every time Gausium reissues the datasheet with a new field. This view is
 * read-only and renders whatever columns exist, so the shape is carried as a map
 * built straight from {@code to_jsonb(row)} — add a column to the table and it
 * appears in the UI with no Java change at all.
 *
 * <p>That trade is deliberate and scoped to <em>display</em>. When
 * {@code RecommendationService} moves off the old {@code robot_specs} table it will
 * need a properly typed entity, because the AI compares fields rather than printing
 * them. This endpoint does not block that work and should not be reused for it.
 *
 * <p>Labels, units and grouping are not stored anywhere — the spreadsheet carried
 * units in a header row that was never persisted — so they live in the frontend
 * alongside the rest of the presentation logic.
 */
public record RobotSpecMatrixRow(
        UUID robotId,
        String brand,
        String model,
        String testStatus,
        Map<String, Object> specs
) {
}
