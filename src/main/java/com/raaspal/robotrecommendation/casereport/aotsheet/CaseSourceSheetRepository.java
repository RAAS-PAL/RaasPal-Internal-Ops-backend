package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseSourceSheetRepository extends JpaRepository<CaseSourceSheet, UUID> {

    Optional<CaseSourceSheet> findBySourceKey(String sourceKey);
}
