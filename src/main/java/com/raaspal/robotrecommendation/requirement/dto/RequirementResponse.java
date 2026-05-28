package com.raaspal.robotrecommendation.requirement.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.InputSource;
import com.raaspal.robotrecommendation.common.enums.RequirementStatus;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;

import java.time.LocalDateTime;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        RobotType robotType,
        String title,
        String description,
        Environment environment,
        String[] cleaningFunctions,
        String[] floorTypes,
        Integer minPassableWidthMm,
        Integer coverageAreaSqm,
        BudgetBand budgetBand,
        String priorityNotes,
        InputSource inputSource,
        UUID sourceFileId,
        RequirementStatus status,
        UUID createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RequirementResponse from(Requirement requirement) {
        return new RequirementResponse(
                requirement.getId(),
                requirement.getRobotType(),
                requirement.getTitle(),
                requirement.getDescription(),
                requirement.getEnvironment(),
                requirement.getCleaningFunctions(),
                requirement.getFloorTypes(),
                requirement.getMinPassableWidthMm(),
                requirement.getCoverageAreaSqm(),
                requirement.getBudgetBand(),
                requirement.getPriorityNotes(),
                requirement.getInputSource(),
                requirement.getSourceFile() == null ? null : requirement.getSourceFile().getId(),
                requirement.getStatus(),
                requirement.getCreatedBy() == null ? null : requirement.getCreatedBy().getId(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt()
        );
    }
}
