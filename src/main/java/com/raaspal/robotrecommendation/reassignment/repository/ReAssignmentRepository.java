package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReAssignmentRepository extends JpaRepository<ReAssignment, UUID> {

    List<ReAssignment> findByBoardIdAndEndedAtIsNull(String boardId);

    Optional<ReAssignment> findByBoardIdAndItemIdAndEndedAtIsNull(String boardId, String itemId);

    List<ReAssignment> findByBoardIdAndItemIdOrderByApprovedAtDesc(String boardId, String itemId);

    List<ReAssignment> findTop200ByBoardIdOrderByApprovedAtDesc(String boardId);

    List<ReAssignment> findByEngineerIdOrderByApprovedAtDesc(UUID engineerId);
}
