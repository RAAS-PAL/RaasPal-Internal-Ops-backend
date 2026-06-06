package com.raaspal.robotrecommendation.proposal.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.proposal.dto.GenerateProposalRequest;
import com.raaspal.robotrecommendation.proposal.dto.GeneratedProposalResponse;
import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import com.raaspal.robotrecommendation.proposal.service.GeneratedProposalService;
import com.raaspal.robotrecommendation.proposal.service.ProposalExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/proposals")
@RequiredArgsConstructor
public class GeneratedProposalController {

    private final GeneratedProposalService generatedProposalService;
    private final ProposalExportService proposalExportService;

    @GetMapping
    public ApiResponse<PagedResponse<GeneratedProposalResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(generatedProposalService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<GeneratedProposalResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(generatedProposalService.getById(id));
    }

    @GetMapping("/{id}/export/pptx")
    public ResponseEntity<byte[]> exportPptx(@PathVariable UUID id) throws IOException {
        GeneratedProposal proposal = generatedProposalService.getEntityForExport(id);
        byte[] pptx = proposalExportService.exportToPptx(proposal);
        String filename = "proposal-" + id + ".pptx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .body(pptx);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        generatedProposalService.delete(id);
        return ApiResponse.success("Proposal deleted", null);
    }

    @PostMapping("/generate")
    public ApiResponse<GeneratedProposalResponse> generate(
            @Valid @RequestBody GenerateProposalRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.success("Proposal generated",
                generatedProposalService.generate(request, principal.getId()));
    }
}
