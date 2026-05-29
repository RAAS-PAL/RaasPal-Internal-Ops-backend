package com.raaspal.robotrecommendation.proposal.repository;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GeneratedProposalRepository extends JpaRepository<GeneratedProposal, UUID> {

    Page<GeneratedProposal> findByRecommendationId(UUID recommendationId, Pageable pageable);

    Page<GeneratedProposal> findByRequirementId(UUID requirementId, Pageable pageable);

    Page<GeneratedProposal> findByGeneratedById(UUID generatedById, Pageable pageable);
}
