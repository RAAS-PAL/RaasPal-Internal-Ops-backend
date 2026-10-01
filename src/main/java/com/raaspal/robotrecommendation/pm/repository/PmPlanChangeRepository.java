package com.raaspal.robotrecommendation.pm.repository;

import com.raaspal.robotrecommendation.pm.entity.PmPlanChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PmPlanChangeRepository extends JpaRepository<PmPlanChange, UUID> {

    /** Newest first, for the planner's "Recent moves". */
    List<PmPlanChange> findAllByOrderByChangedAtDesc(Pageable pageable);

    /** The visit's latest change, the only one that may be undone. */
    Optional<PmPlanChange> findFirstByPmVisitIdOrderByChangedAtDesc(UUID pmVisitId);

    boolean existsByUndoesChangeId(UUID undoesChangeId);
}
