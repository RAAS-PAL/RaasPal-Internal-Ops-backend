package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.CustomerReportExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerReportExclusionRepository extends JpaRepository<CustomerReportExclusion, UUID> {

    List<CustomerReportExclusion> findAllByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);

    void deleteByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);
}
