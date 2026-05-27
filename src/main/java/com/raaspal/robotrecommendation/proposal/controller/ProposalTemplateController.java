package com.raaspal.robotrecommendation.proposal.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateRequest;
import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateResponse;
import com.raaspal.robotrecommendation.proposal.service.ProposalTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/proposal-templates")
@RequiredArgsConstructor
public class ProposalTemplateController {

    private final ProposalTemplateService proposalTemplateService;

    @GetMapping
    public ApiResponse<PagedResponse<ProposalTemplateResponse>> getAll(
            Pageable pageable,
            @RequestParam(defaultValue = "false") boolean activeOnly
    ) {
        return ApiResponse.success(PagedResponse.of(
                activeOnly
                        ? proposalTemplateService.getActive(pageable)
                        : proposalTemplateService.getAll(pageable)
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProposalTemplateResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(proposalTemplateService.getById(id));
    }

    @PostMapping
    public ApiResponse<ProposalTemplateResponse> create(
            @Valid @RequestBody ProposalTemplateRequest request,
            @RequestParam(required = false) UUID createdById
    ) {
        return ApiResponse.success("Proposal template created", proposalTemplateService.create(request, createdById));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProposalTemplateResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ProposalTemplateRequest request
    ) {
        return ApiResponse.success("Proposal template updated", proposalTemplateService.update(id, request));
    }
}
