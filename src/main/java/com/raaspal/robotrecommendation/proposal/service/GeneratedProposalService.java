package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.ai.service.ProposalGenerationAiService;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.proposal.dto.GenerateProposalRequest;
import com.raaspal.robotrecommendation.proposal.dto.GeneratedProposalResponse;
import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateResponse;
import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import com.raaspal.robotrecommendation.proposal.entity.ProposalTemplate;
import com.raaspal.robotrecommendation.proposal.repository.GeneratedProposalRepository;
import com.raaspal.robotrecommendation.recommendation.dto.RecommendationItemResponse;
import com.raaspal.robotrecommendation.recommendation.entity.Recommendation;
import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import com.raaspal.robotrecommendation.recommendation.service.RecommendationService;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;
import com.raaspal.robotrecommendation.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GeneratedProposalService {

    private final GeneratedProposalRepository generatedProposalRepository;
    private final RecommendationService recommendationService;
    private final ProposalTemplateService proposalTemplateService;
    private final UserService userService;
    private final ProposalGenerationAiService proposalGenerationAiService;

    @Transactional(readOnly = true)
    public Page<GeneratedProposalResponse> getAll(Pageable pageable) {
        return generatedProposalRepository.findAll(pageable).map(GeneratedProposalResponse::from);
    }

    @Transactional(readOnly = true)
    public GeneratedProposal getEntity(UUID id) {
        return generatedProposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProposal", "id", id));
    }

    @Transactional(readOnly = true)
    public GeneratedProposal getEntityForExport(UUID id) {
        return generatedProposalRepository.findByIdForExport(id)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProposal", "id", id));
    }

    @Transactional(readOnly = true)
    public GeneratedProposalResponse getById(UUID id) {
        return GeneratedProposalResponse.from(getEntity(id));
    }

    @Transactional
    public void delete(UUID id) {
        if (!generatedProposalRepository.existsById(id)) {
            throw new ResourceNotFoundException("GeneratedProposal", "id", id);
        }
        generatedProposalRepository.deleteById(id);
    }

    @Transactional
    public GeneratedProposalResponse generate(GenerateProposalRequest request, UUID generatedById) {
        RecommendationItem item = recommendationService.getItemEntity(request.recommendationItemId());
        Recommendation recommendation = item.getRecommendation();
        Requirement requirement = recommendation.getRequirement();
        ProposalTemplate template = request.proposalTemplateId() == null
                ? null
                : proposalTemplateService.getEntity(request.proposalTemplateId());

        ProposalTemplateResponse templateResponse = template == null ? null : ProposalTemplateResponse.from(template);
        AiProposalResult result = proposalGenerationAiService.generateProposal(new AiProposalRequest(
                RequirementResponse.from(requirement),
                RecommendationItemResponse.from(item),
                templateResponse
        ));

        GeneratedProposal proposal = GeneratedProposal.builder()
                .recommendation(recommendation)
                .recommendationItem(item)
                .requirement(requirement)
                .proposalTemplate(template)
                .title(result.title())
                .proposalContent(result.proposalContent())
                .contentFormat(result.contentFormat() == null ? "TEXT" : result.contentFormat())
                .status("GENERATED")
                .generatedBy(generatedById == null ? null : userService.getEntity(generatedById))
                .build();

        return GeneratedProposalResponse.from(generatedProposalRepository.save(proposal));
    }
}
