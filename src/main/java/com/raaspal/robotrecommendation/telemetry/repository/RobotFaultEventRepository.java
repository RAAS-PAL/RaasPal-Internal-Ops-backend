package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RobotFaultEventRepository extends JpaRepository<RobotFaultEvent, UUID> {

    /** Every fault still open for a brand - what the poller compares each snapshot against. */
    List<RobotFaultEvent> findByBrandAndClearedAtIsNull(String brand);

    /**
     * One robot's faults that overlap {@code [from, to)}: started before the end and not
     * cleared before the start. Oldest first.
     */
    @Query("""
           SELECT f FROM RobotFaultEvent f
            WHERE f.brand = :brand
              AND f.robotId = :robotId
              AND f.firstSeenAt < :to
              AND (f.clearedAt IS NULL OR f.clearedAt > :from)
            ORDER BY f.firstSeenAt
           """)
    List<RobotFaultEvent> findOverlapping(@Param("brand") String brand,
                                          @Param("robotId") String robotId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);

    /** The same across every robot of a brand, for the fault log endpoint. */
    @Query("""
           SELECT f FROM RobotFaultEvent f
            WHERE f.brand = :brand
              AND f.firstSeenAt < :to
              AND (f.clearedAt IS NULL OR f.clearedAt > :from)
            ORDER BY f.firstSeenAt DESC
           """)
    List<RobotFaultEvent> findOverlappingAll(@Param("brand") String brand,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    /** The earliest row ever written - "recording since". Null before the first poll. */
    @Query("SELECT MIN(f.firstSeenAt) FROM RobotFaultEvent f WHERE f.brand = :brand")
    Instant findRecordingSince(@Param("brand") String brand);
}
