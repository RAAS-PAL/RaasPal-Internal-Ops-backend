package com.raaspal.robotrecommendation.requirement.service;

import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.ai.service.RequirementExtractionService;
import com.raaspal.robotrecommendation.common.enums.InputSource;
import com.raaspal.robotrecommendation.common.enums.RequirementStatus;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.file.service.FileUploadService;
import com.raaspal.robotrecommendation.requirement.dto.ExtractRequirementRequest;
import com.raaspal.robotrecommendation.requirement.dto.RequirementRequest;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;
import com.raaspal.robotrecommendation.requirement.repository.RequirementRepository;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RequirementService {

    private final RequirementRepository requirementRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final FileUploadService fileUploadService;
    private final UserService userService;
    private final RequirementExtractionService requirementExtractionService;

    @Transactional(readOnly = true)
    public Page<RequirementResponse> getAll(Pageable pageable) {
        return requirementRepository.findAll(pageable).map(RequirementResponse::from);
    }

    @Transactional(readOnly = true)
    public Requirement getEntity(UUID id) {
        return requirementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Requirement", "id", id));
    }

    @Transactional(readOnly = true)
    public RequirementResponse getById(UUID id) {
        return RequirementResponse.from(getEntity(id));
    }

    @Transactional
    public RequirementResponse create(RequirementRequest request, UUID createdById) {
        Requirement requirement = Requirement.builder()
                .customerProfile(getCustomerProfile(request.customerProfileId()))
                .robotType(request.robotType())
                .title(request.title())
                .description(request.description())
                .environment(request.environment())
                .cleaningFunctions(request.cleaningFunctions())
                .floorTypes(request.floorTypes())
                .minPassableWidthMm(request.minPassableWidthMm())
                .coverageAreaSqm(request.coverageAreaSqm())
                .budgetBand(request.budgetBand())
                .priorityNotes(request.priorityNotes())
                .inputSource(request.inputSource() == null ? InputSource.WEB_FORM : request.inputSource())
                .sourceFile(request.sourceFileId() == null ? null : fileUploadService.getEntity(request.sourceFileId()))
                .status(request.status() == null ? RequirementStatus.DRAFT : request.status())
                .createdBy(createdById == null ? null : userService.getEntity(createdById))
                .build();

        return RequirementResponse.from(requirementRepository.save(requirement));
    }

    @Transactional
    public RequirementResponse extractFromFile(UUID fileId, ExtractRequirementRequest request, UUID createdById) {
        FileUpload fileUpload = fileUploadService.getEntity(fileId);
        ExtractedRequirementData extracted = requirementExtractionService.extract(fileUpload, request.robotType());

        User createdBy = createdById == null ? null : userService.getEntity(createdById);
        Requirement requirement = Requirement.builder()
                .customerProfile(getCustomerProfile(request.customerProfileId()))
                .robotType(extracted.robotType())
                .title(extracted.title())
                .description(extracted.description())
                .environment(extracted.environment())
                .cleaningFunctions(toArray(extracted.cleaningFunctions()))
                .floorTypes(toArray(extracted.floorTypes()))
                .minPassableWidthMm(extracted.minPassableWidthMm())
                .coverageAreaSqm(extracted.coverageAreaSqm())
                .budgetBand(extracted.budgetBand())
                .priorityNotes(extracted.priorityNotes())
                .inputSource(InputSource.FILE_EXTRACTED)
                .sourceFile(fileUpload)
                .status(RequirementStatus.DRAFT)
                .createdBy(createdBy)
                .build();

        return RequirementResponse.from(requirementRepository.save(requirement));
    }

    @Transactional
    public RequirementResponse update(UUID id, RequirementRequest request) {
        Requirement requirement = getEntity(id);
        requirement.setCustomerProfile(getCustomerProfile(request.customerProfileId()));
        requirement.setRobotType(request.robotType());
        requirement.setTitle(request.title());
        requirement.setDescription(request.description());
        requirement.setEnvironment(request.environment());
        requirement.setCleaningFunctions(request.cleaningFunctions());
        requirement.setFloorTypes(request.floorTypes());
        requirement.setMinPassableWidthMm(request.minPassableWidthMm());
        requirement.setCoverageAreaSqm(request.coverageAreaSqm());
        requirement.setBudgetBand(request.budgetBand());
        requirement.setPriorityNotes(request.priorityNotes());
        requirement.setInputSource(request.inputSource() == null ? requirement.getInputSource() : request.inputSource());
        requirement.setSourceFile(request.sourceFileId() == null ? null : fileUploadService.getEntity(request.sourceFileId()));
        requirement.setStatus(request.status() == null ? requirement.getStatus() : request.status());

        return RequirementResponse.from(requirementRepository.save(requirement));
    }

    private CustomerProfile getCustomerProfile(UUID id) {
        return customerProfileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", id));
    }

    private String[] toArray(java.util.List<String> values) {
        return values == null ? null : values.toArray(String[]::new);
    }
}
