package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReTicketHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReTicketHoldRepository extends JpaRepository<ReTicketHold, java.util.UUID> {

    List<ReTicketHold> findByBoardIdAndReleasedAtIsNull(String boardId);

    Optional<ReTicketHold> findByBoardIdAndItemIdAndReleasedAtIsNull(String boardId, String itemId);
}
