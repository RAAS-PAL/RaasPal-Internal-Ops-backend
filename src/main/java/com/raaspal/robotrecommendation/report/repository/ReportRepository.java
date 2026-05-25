package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByRecommendationId(UUID recommendationId);
}