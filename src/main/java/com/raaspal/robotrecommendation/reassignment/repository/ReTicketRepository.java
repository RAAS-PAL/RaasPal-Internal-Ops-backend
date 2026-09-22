package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReTicketRepository extends JpaRepository<ReTicket, ReTicket.Key> {

    List<ReTicket> findByBoardIdAndOpenTrue(String boardId);

    List<ReTicket> findByBoardId(String boardId);
}
