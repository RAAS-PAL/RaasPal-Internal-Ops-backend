package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationOption;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptRules;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@ConditionalOnExpression("'${app.anthropic.api-key:}' == ''")
public class MockAiService implements RequirementExtractionService, RobotRecommendationAiService, ProposalGenerationAiService, TranslationAiService {

    @Override
    public ExtractedRequirementData extract(FileUpload fileUpload, RobotType robotType) {
        return new ExtractedRequirementData(
                robotType,
                "Extracted requirement from " + fileUpload.getOriginalFilename(),
                "Mock extraction is active. Review the uploaded survey data before generating recommendations.",
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                AiPromptRules.NEEDS_CONFIRMATION,
                List.of(
                        "Confirm site environment.",
                        "Confirm cleaning area per shift.",
                        "Confirm floor type and passable width."
                )
        );
    }

    @Override
    public AiRecommendationResult recommend(
            RequirementResponse requirement,
            List<RobotCatalogData> robotCatalog,
            int optionCount
    ) {
        if (robotCatalog.isEmpty()) {
            return new AiRecommendationResult(
                    "No robot catalog data was provided from the database.",
                    List.of()
            );
        }

        List<AiRecommendationOption> options = new ArrayList<>();
        List<RobotCatalogData> rankedCatalog = robotCatalog.stream()
                .sorted(Comparator
                        .comparing((RobotCatalogData robot) -> robot.spec() == null)
                        .thenComparing(robot -> robot.brand() + " " + robot.model()))
                .toList();
        int limit = Math.min(optionCount, rankedCatalog.size());
        for (int index = 0; index < limit; index++) {
            RobotCatalogData robot = rankedCatalog.get(index);
            options.add(new AiRecommendationOption(
                    robot.robotId(),
                    index + 1,
                    fitLevel(index, robot),
                    robot.brand() + " " + robot.model() + " solution",
                    proposalSummary(robot),
                    whyRecommended(requirement, robot),
                    null,
                    matchedRequirements(requirement, robot),
                    businessValue(robot),
                    limitations(robot),
                    missingInformation(requirement, robot),
                    "Confirm missing survey details, then generate a proposal from the selected option."
            ));
        }

        return new AiRecommendationResult(
                "Mock recommendation generated from " + robotCatalog.size() + " database robot record(s).",
                options
        );
    }

    @Override
    public AiProposalResult generateProposal(AiProposalRequest request) {
        String robotName = request.selectedOption().robot().brand() + " " + request.selectedOption().robot().model();
        String title = request.selectedOption().proposalTitle() == null
                ? robotName + " Proposal"
                : request.selectedOption().proposalTitle();
        String content = """
                ## Executive Summary
                %s

                ## Customer Requirement
                %s

                ## Recommended Robot
                %s

                ## Why Recommended
                %s

                ## Business Value
                %s

                ## Limitations / Missing Information
                %s

                ## Suggested Next Step
                %s
                """.formatted(
                title,
                valueOrNeedsConfirmation(request.requirement().description()),
                robotName,
                valueOrNeedsConfirmation(request.selectedOption().whyRecommended()),
                valueOrNeedsConfirmation(request.selectedOption().businessValue()),
                valueOrNeedsConfirmation(request.selectedOption().missingInformation()),
                valueOrNeedsConfirmation(request.selectedOption().suggestedNextStep())
        );

        return new AiProposalResult(title, content, "TEXT");
    }

    @Override
    public List<String> translateToThai(List<String> texts) {
        return texts == null ? List.of() : texts;
    }

    private String valueOrNeedsConfirmation(String value) {
        return value == null || value.isBlank() ? AiPromptRules.NEEDS_CONFIRMATION : value;
    }

    private String fitLevel(int index, RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Needs confirmation";
        }
        return index == 0 ? "Best fit" : "Possible fit";
    }

    private String proposalSummary(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Robot exists in the database, but specifications are incomplete.";
        }
        return "Uses database robot specifications for a first-pass RAASPAL solution option.";
    }

    private String whyRecommended(RequirementResponse requirement, RobotCatalogData robot) {
        StringBuilder builder = new StringBuilder();
        builder.append("Recommended because ")
                .append(robot.brand())
                .append(" ")
                .append(robot.model())
                .append(" is available in the database for ")
                .append(requirement.robotType())
                .append(" requirements.");

        RobotSpecResponse spec = robot.spec();
        if (spec != null && spec.widthCleaningMm() != null) {
            builder.append(" Cleaning width: ").append(spec.widthCleaningMm()).append(" mm.");
        }
        if (spec != null && spec.minimumPassableWidthMm() != null) {
            builder.append(" Minimum passable width: ").append(spec.minimumPassableWidthMm()).append(" mm.");
        }
        return builder.toString();
    }

    private String matchedRequirements(RequirementResponse requirement, RobotCatalogData robot) {
        List<String> matches = new ArrayList<>();
        if (requirement.robotType() == robot.robotType()) {
            matches.add("Robot type matches: " + requirement.robotType());
        }
        if (requirement.minPassableWidthMm() != null
                && robot.spec() != null
                && robot.spec().minimumPassableWidthMm() != null) {
            matches.add("Passable width can be checked against database value: "
                    + robot.spec().minimumPassableWidthMm() + " mm");
        }
        if (requirement.coverageAreaSqm() != null) {
            matches.add("Coverage area requested: " + requirement.coverageAreaSqm() + " sqm");
        }
        return matches.isEmpty() ? AiPromptRules.NEEDS_CONFIRMATION : String.join("; ", matches);
    }

    private String businessValue(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Can be reviewed as a possible option after robot specifications are confirmed.";
        }
        return "Helps RAASPAL quickly prepare a data-backed customer proposal using verified catalog information.";
    }

    private String limitations(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return AiPromptRules.NEEDS_CONFIRMATION;
        }
        return "Mock AI does not perform weighted scoring. Final fit must be reviewed by RAASPAL team.";
    }

    private String missingInformation(RequirementResponse requirement, RobotCatalogData robot) {
        List<String> missing = new ArrayList<>();
        if (requirement.environment() == null) {
            missing.add("site environment");
        }
        if (requirement.coverageAreaSqm() == null) {
            missing.add("coverage area");
        }
        if (requirement.floorTypes() == null || requirement.floorTypes().length == 0) {
            missing.add("floor types");
        }
        if (robot.spec() == null) {
            missing.add("robot specifications");
        }
        return missing.isEmpty() ? "No major missing information detected by mock AI." : String.join(", ", missing);
    }
}
