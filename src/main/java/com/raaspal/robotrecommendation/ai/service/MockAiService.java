package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationOption;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MockAiService implements RequirementExtractionService, RobotRecommendationAiService, ProposalGenerationAiService {

    private static final String NEEDS_CONFIRMATION = "Needs confirmation.";

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
                NEEDS_CONFIRMATION,
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
        int limit = Math.min(optionCount, robotCatalog.size());
        for (int index = 0; index < limit; index++) {
            RobotCatalogData robot = robotCatalog.get(index);
            options.add(new AiRecommendationOption(
                    robot.robotId(),
                    index + 1,
                    index == 0 ? "Best fit" : "Possible fit",
                    robot.brand() + " " + robot.model() + " solution",
                    "Uses only database robot data. Confirm missing customer details before final proposal.",
                    "Recommended because this robot is available in the database for the requested robot type.",
                    requirement.description() == null ? NEEDS_CONFIRMATION : requirement.description(),
                    "Can support RAASPAL team review and proposal preparation once requirements are confirmed.",
                    NEEDS_CONFIRMATION,
                    NEEDS_CONFIRMATION,
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
                %s

                Customer Requirement
                %s

                Recommended Robot
                %s

                Why Recommended
                %s

                Business Value
                %s

                Limitations / Missing Information
                %s

                Suggested Next Step
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

    private String valueOrNeedsConfirmation(String value) {
        return value == null || value.isBlank() ? NEEDS_CONFIRMATION : value;
    }
}
