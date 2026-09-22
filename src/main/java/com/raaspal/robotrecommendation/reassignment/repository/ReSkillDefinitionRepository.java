package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReSkillDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReSkillDefinitionRepository extends JpaRepository<ReSkillDefinition, String> {

    List<ReSkillDefinition> findAllByOrderByOrdinalAsc();
}
