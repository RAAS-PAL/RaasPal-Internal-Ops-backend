package com.raaspal.robotrecommendation.kpi.repository;

import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CaseTicketSyncRunRepository extends JpaRepository<CaseTicketSyncRun, UUID> {

    List<CaseTicketSyncRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
