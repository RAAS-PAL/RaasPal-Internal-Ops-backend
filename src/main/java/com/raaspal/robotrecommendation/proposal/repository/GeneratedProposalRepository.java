package com.raaspal.robotrecommendation.proposal.repository;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GeneratedProposalRepository extends JpaRepository<GeneratedProposal, UUID> {

    @Query("SELECT p FROM GeneratedProposal p " +
           "LEFT JOIN FETCH p.recommendation rec " +
           "LEFT JOIN FETCH p.recommendationItem ri " +
           "LEFT JOIN FETCH ri.robot " +
           "WHERE p.id = :id")
    Optional<GeneratedProposal> findByIdForExport(@Param("id") UUID id);

    Page<GeneratedProposal> findByRecommendationId(UUID recommendationId, Pageable pageable);

    Page<GeneratedProposal> findByRequirementId(UUID requirementId, Pageable pageable);

    Page<GeneratedProposal> findByGeneratedById(UUID generatedById, Pageable pageable);
}
