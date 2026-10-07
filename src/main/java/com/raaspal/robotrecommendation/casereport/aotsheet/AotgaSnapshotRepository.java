package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AotgaSnapshotRepository extends JpaRepository<AotgaSnapshot, LocalDate> {

    /** The first day there is a copy of: where AOTGA's history starts. */
    Optional<AotgaSnapshot> findFirstByOrderByRunDateAsc();

    /** The last two weeks' copies on or before a day, newest first. */
    java.util.List<AotgaSnapshot> findTop14ByRunDateLessThanEqualOrderByRunDateDesc(LocalDate day);

    /** The latest copy on or before a day. */
    Optional<AotgaSnapshot> findFirstByRunDateLessThanEqualOrderByRunDateDesc(LocalDate day);
}
