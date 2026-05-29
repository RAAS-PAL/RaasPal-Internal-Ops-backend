package com.raaspal.robotrecommendation.proposal.repository;

import com.raaspal.robotrecommendation.proposal.entity.ProposalTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProposalTemplateRepository extends JpaRepository<ProposalTemplate, UUID> {

    Page<ProposalTemplate> findByActiveTrue(Pageable pageable);

    Page<ProposalTemplate> findByCreatedById(UUID createdById, Pageable pageable);
}
