package com.raaspal.robotrecommendation.recommendation.service;

import com.raaspal.robotrecommendation.ai.dto.AiRecommendationOption;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.ai.service.RobotRecommendationAiService;
import com.raaspal.robotrecommendation.common.enums.RecommendationStatus;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.recommendation.dto.GenerateRecommendationRequest;
import com.raaspal.robotrecommendation.recommendation.dto.RecommendationItemResponse;
import com.raaspal.robotrecommendation.recommendation.dto.RecommendationResponse;
import com.raaspal.robotrecommendation.recommendation.entity.Recommendation;
import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import com.raaspal.robotrecommendation.recommendation.repository.RecommendationItemRepository;
import com.raaspal.robotrecommendation.recommendation.repository.RecommendationRepository;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;
import com.raaspal.robotrecommendation.requirement.service.RequirementService;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecResponse;
import com.raaspal.robotrecommendation.robot.entity.Robot;
import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import com.raaspal.robotrecommendation.robot.repository.RobotRepository;
import com.raaspal.robotrecommendation.robot.repository.RobotSpecRepository;
import com.raaspal.robotrecommendation.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecommendationRepository recommendationRepository;
    private final RecommendationItemRepository recommendationItemRepository;
    private final RequirementService requirementService;
    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;
    private final UserService userService;
    private final RobotRecommendationAiService robotRecommendationAiService;

    @Transactional(readOnly = true)
    public Page<RecommendationResponse> getAll(Pageable pageable) {
        return recommendationRepository.findAll(pageable).map(RecommendationResponse::from);
    }

    @Transactional(readOnly = true)
    public Recommendation getEntity(UUID id) {
        return recommendationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recommendation", "id", id));
    }

    @Transactional(readOnly = true)
    public RecommendationItem getItemEntity(UUID id) {
        return recommendationItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RecommendationOption", "id", id));
    }

    @Transactional(readOnly = true)
    public RecommendationResponse getById(UUID id) {
        Recommendation recommendation = getEntity(id);
        List<RecommendationItemResponse> options = recommendationItemRepository
                .findByRecommendationIdOrderByRankPositionAsc(id)
                .stream()
                .map(RecommendationItemResponse::from)
                .toList();
        return RecommendationResponse.from(recommendation, options);
    }

    @Transactional
    public RecommendationResponse generate(
            UUID requirementId,
            GenerateRecommendationRequest request,
            UUID createdById
    ) {
        Requirement requirement = requirementService.getEntity(requirementId);
        List<Robot> robots = robotRepository.findAll().stream()
                .filter(robot -> robot.getRobotType() == requirement.getRobotType())
                .toList();
        List<RobotCatalogData> catalog = robots.stream().map(this::toCatalogData).toList();
        int optionCount = request == null || request.optionCount() == null ? 3 : request.optionCount();

        Recommendation recommendation = Recommendation.builder()
                .requirement(requirement)
                .status(RecommendationStatus.IN_PROGRESS)
                .createdBy(createdById == null ? null : userService.getEntity(createdById))
                .build();
        Recommendation savedRecommendation = recommendationRepository.save(recommendation);

        AiRecommendationResult result = robotRecommendationAiService.recommend(
                RequirementResponse.from(requirement),
                catalog,
                optionCount
        );

        Map<UUID, Robot> robotsById = robots.stream()
                .collect(Collectors.toMap(Robot::getId, Function.identity()));
        List<RecommendationItem> items = result.options().stream()
                .map(option -> toRecommendationItem(savedRecommendation, option, robotsById))
                .sorted(Comparator.comparing(RecommendationItem::getRankPosition))
                .toList();

        recommendationItemRepository.saveAll(items);
        savedRecommendation.setAiExplanation(result.aiExplanation());
        savedRecommendation.setStatus(RecommendationStatus.COMPLETED);
        Recommendation completedRecommendation = recommendationRepository.save(savedRecommendation);

        List<RecommendationItemResponse> optionResponses = recommendationItemRepository
                .findByRecommendationIdOrderByRankPositionAsc(completedRecommendation.getId())
                .stream()
                .map(RecommendationItemResponse::from)
                .toList();
        return RecommendationResponse.from(completedRecommendation, optionResponses);
    }

    private RecommendationItem toRecommendationItem(
            Recommendation recommendation,
            AiRecommendationOption option,
            Map<UUID, Robot> robotsById
    ) {
        Robot robot = robotsById.get(option.robotId());
        if (robot == null) {
            throw new IllegalArgumentException("AI returned a robot that was not provided from the database");
        }

        return RecommendationItem.builder()
                .recommendation(recommendation)
                .robot(robot)
                .rankPosition(option.rankPosition())
                .totalScore(null)
                .aiReasoning(option.whyRecommended())
                .fitLevel(option.fitLevel())
                .proposalTitle(option.proposalTitle())
                .proposalSummary(option.proposalSummary())
                .whyRecommended(option.whyRecommended())
                .matchedRequirements(option.matchedRequirements())
                .businessValue(option.businessValue())
                .limitations(option.limitations())
                .missingInformation(option.missingInformation())
                .suggestedNextStep(option.suggestedNextStep())
                .build();
    }

    private RobotCatalogData toCatalogData(Robot robot) {
        RobotSpecResponse spec = robotSpecRepository.findByRobot_Id(robot.getId())
                .map(RobotSpecResponse::from)
                .orElse(null);

        return new RobotCatalogData(
                robot.getId(),
                robot.getBrand(),
                robot.getModel(),
                robot.getRobotType(),
                robot.getTestStatus(),
                robot.getPriceBand(),
                spec
        );
    }
}
