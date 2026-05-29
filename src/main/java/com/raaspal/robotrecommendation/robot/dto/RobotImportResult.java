package com.raaspal.robotrecommendation.robot.dto;

import java.util.List;

public record RobotImportResult(
        int totalRows,
        int imported,
        int updated,
        int skipped,
        List<String> errors
) {
}
