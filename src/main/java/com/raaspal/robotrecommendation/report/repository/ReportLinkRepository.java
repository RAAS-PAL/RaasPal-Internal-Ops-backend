package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.ReportLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportLinkRepository extends JpaRepository<ReportLink, UUID> {

    Optional<ReportLink> findByToken(String token);

    Optional<ReportLink> findBySerialNumberAndReportMonth(String serialNumber, String reportMonth);
}
