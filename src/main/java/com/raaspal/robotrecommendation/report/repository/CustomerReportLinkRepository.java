package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.CustomerReportLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerReportLinkRepository extends JpaRepository<CustomerReportLink, UUID> {

    Optional<CustomerReportLink> findByToken(String token);

    Optional<CustomerReportLink> findByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);
}
