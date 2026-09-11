package com.raaspal.robotrecommendation.pm.repository;

import com.raaspal.robotrecommendation.pm.entity.PmContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PmContractRepository extends JpaRepository<PmContract, UUID> {

    List<PmContract> findBySourceBoardId(String sourceBoardId);

    /**
     * Flags contracts that stopped coming back from monday.
     *
     * <p>Identified by their sync stamp rather than by a list of the ids just seen:
     * every row this run touched has {@code lastSyncedAt} set to the run's start, so
     * anything older was not in the board any more. The alternative, NOT IN over the
     * seen ids, would put several thousand literals in one statement and needs a
     * second query for the empty-board case.
     *
     * <p>Marked, not deleted: a contract removed from a board still owns real visit
     * history, and a cascade delete would take it with it.
     */
    @Modifying
    @Query("""
            UPDATE PmContract c
               SET c.isPresent = false
             WHERE c.sourceBoardId = :boardId
               AND c.isPresent = true
               AND c.lastSyncedAt < :runStart
            """)
    int markAbsentBefore(@Param("boardId") String boardId, @Param("runStart") OffsetDateTime runStart);
}
