package com.raaspal.robotrecommendation.ai.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.RobotType;

import java.util.List;

public record ExtractedRequirementData(
        RobotType robotType,
        String title,
        String description,
        Environment environment,
        List<String> cleaningFunctions,
        List<String> floorTypes,
        Integer minPassableWidthMm,
        Integer coverageAreaSqm,
        BudgetBand budgetBand,
        String priorityNotes,
        List<String> missingQuestions
) {
}
