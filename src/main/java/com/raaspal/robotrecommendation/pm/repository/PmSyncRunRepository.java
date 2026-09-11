package com.raaspal.robotrecommendation.pm.repository;

import com.raaspal.robotrecommendation.pm.entity.PmSyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PmSyncRunRepository extends JpaRepository<PmSyncRun, UUID> {

    List<PmSyncRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
