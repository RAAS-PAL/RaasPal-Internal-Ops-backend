package com.raaspal.robotrecommendation.mkstock.repository;

import com.raaspal.robotrecommendation.mkstock.entity.MkStockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface MkStockMovementRepository extends JpaRepository<MkStockMovement, UUID> {

    List<MkStockMovement> findTop200ByPartIdOrderByMovedOnDescCreatedAtDesc(UUID partId);

    List<MkStockMovement> findByMovedOnBetween(LocalDate from, LocalDate to);

    List<MkStockMovement> findTop15ByOrderByMovedOnDescCreatedAtDesc();

    List<MkStockMovement> findTop500ByOrderByMovedOnDescCreatedAtDesc();

    /** [partId, latest moved_on] per part. */
    @Query("select m.partId, max(m.movedOn) from MkStockMovement m group by m.partId")
    List<Object[]> lastMovedPerPart();
}
