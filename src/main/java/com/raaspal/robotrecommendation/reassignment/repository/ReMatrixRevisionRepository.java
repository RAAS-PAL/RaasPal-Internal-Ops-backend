package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReMatrixRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReMatrixRevisionRepository extends JpaRepository<ReMatrixRevision, UUID> {

    List<ReMatrixRevision> findTop20ByOrderByCreatedAtDesc();

    Optional<ReMatrixRevision> findByLabel(String label);

    boolean existsByLabel(String label);

    long countBySource(String source);
}
