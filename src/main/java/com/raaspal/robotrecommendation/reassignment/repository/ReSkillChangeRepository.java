package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReSkillChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReSkillChangeRepository extends JpaRepository<ReSkillChange, Long> {

    List<ReSkillChange> findByRevisionId(UUID revisionId);

    List<ReSkillChange> findTop50ByEngineerIdOrderByChangedAtDesc(UUID engineerId);

    long countByRevisionId(UUID revisionId);
}
