package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateRequest;
import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateResponse;
import com.raaspal.robotrecommendation.proposal.entity.ProposalTemplate;
import com.raaspal.robotrecommendation.proposal.repository.ProposalTemplateRepository;
import com.raaspal.robotrecommendation.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProposalTemplateService {

    private final ProposalTemplateRepository proposalTemplateRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public Page<ProposalTemplateResponse> getAll(Pageable pageable) {
        return proposalTemplateRepository.findAll(pageable).map(ProposalTemplateResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ProposalTemplateResponse> getActive(Pageable pageable) {
        return proposalTemplateRepository.findByActiveTrue(pageable).map(ProposalTemplateResponse::from);
    }

    @Transactional(readOnly = true)
    public ProposalTemplate getEntity(UUID id) {
        return proposalTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProposalTemplate", "id", id));
    }

    @Transactional(readOnly = true)
    public ProposalTemplateResponse getById(UUID id) {
        return ProposalTemplateResponse.from(getEntity(id));
    }

    @Transactional
    public ProposalTemplateResponse create(ProposalTemplateRequest request, UUID createdById) {
        ProposalTemplate template = ProposalTemplate.builder()
                .name(request.name())
                .description(request.description())
                .templateContent(request.templateContent())
                .contentFormat(request.contentFormat() == null ? "TEXT" : request.contentFormat())
                .active(request.active() == null || request.active())
                .createdBy(createdById == null ? null : userService.getEntity(createdById))
                .build();

        return ProposalTemplateResponse.from(proposalTemplateRepository.save(template));
    }

    @Transactional
    public ProposalTemplateResponse update(UUID id, ProposalTemplateRequest request) {
        ProposalTemplate template = getEntity(id);
        template.setName(request.name());
        template.setDescription(request.description());
        template.setTemplateContent(request.templateContent());
        template.setContentFormat(request.contentFormat() == null ? template.getContentFormat() : request.contentFormat());
        template.setActive(request.active() == null ? template.isActive() : request.active());

        return ProposalTemplateResponse.from(proposalTemplateRepository.save(template));
    }
}
