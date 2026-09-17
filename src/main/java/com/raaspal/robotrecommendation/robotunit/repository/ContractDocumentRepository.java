package com.raaspal.robotrecommendation.robotunit.repository;

import com.raaspal.robotrecommendation.robotunit.entity.ContractDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContractDocumentRepository extends JpaRepository<ContractDocument, UUID> {
}
