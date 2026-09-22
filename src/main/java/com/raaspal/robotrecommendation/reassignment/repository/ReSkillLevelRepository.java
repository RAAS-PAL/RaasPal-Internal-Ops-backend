package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReSkillLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReSkillLevelRepository extends JpaRepository<ReSkillLevel, ReSkillLevel.Key> {

    List<ReSkillLevel> findByEngineerId(UUID engineerId);
}
