package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseReportDefinitionRepository extends JpaRepository<CaseReportDefinition, UUID> {

    /** By the stable code, which is what application code names a report by. */
    Optional<CaseReportDefinition> findByCode(String code);
}
