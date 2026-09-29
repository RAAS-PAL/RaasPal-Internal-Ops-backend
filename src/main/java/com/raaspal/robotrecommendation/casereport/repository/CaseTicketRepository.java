package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseTicketRepository extends JpaRepository<CaseTicket, UUID> {

    /** The natural key the sync upserts on — matches {@code uq_case_ticket_source_item}. */
    Optional<CaseTicket> findBySourceAndSourceItemId(CaseSource source, String sourceItemId);

    /** Everything the sync needs to compare a board against, in one query. */
    List<CaseTicket> findBySourceAndSourceBoardId(CaseSource source, String sourceBoardId);

    /** One sync chunk's stored rows in one round trip. Callers keep the id list to a few hundred. */
    List<CaseTicket> findBySourceAndSourceItemIdIn(CaseSource source, Collection<String> sourceItemIds);

    /**
     * {@code [sourceItemId, sourceUpdatedAt, sourceGroupId]} for every row on a board -
     * what the brand sync compares monday's listing against to decide which tickets to
     * fetch in full. Three columns, not whole rows: {@code raw_columns} is most of a row.
     */
    @Query("""
           SELECT t.sourceItemId, t.sourceUpdatedAt, t.sourceGroupId FROM CaseTicket t
            WHERE t.source = :source
              AND t.sourceBoardId = :boardId
           """)
    List<Object[]> findSyncStamps(@Param("source") CaseSource source, @Param("boardId") String boardId);

    /**
     * Stamp rows the brand sync checked and found unchanged. {@code last_synced_at}
     * still has to move, or "Board synced" on the page would show the last day
     * anything changed rather than the last time anyone looked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE CaseTicket t
              SET t.lastSyncedAt = :syncedAt
            WHERE t.source = :source
              AND t.sourceBoardId = :boardId
              AND t.sourceItemId IN :itemIds
           """)
    int touchSynced(@Param("source") CaseSource source,
                    @Param("boardId") String boardId,
                    @Param("itemIds") Collection<String> itemIds,
                    @Param("syncedAt") LocalDateTime syncedAt);

    /** When one source board (a monday board, or a Google Sheet by its id) was last synced; null if never. */
    @Query("SELECT MAX(t.lastSyncedAt) FROM CaseTicket t WHERE t.source = :source AND t.sourceBoardId = :boardId")
    LocalDateTime lastSyncedAt(@Param("source") CaseSource source, @Param("boardId") String boardId);

    long countBySourceBoardIdAndIsPresentTrue(String sourceBoardId);

    /** What a report actually reads: the open cases on one board. */
    List<CaseTicket> findBySourceBoardIdAndIsPresentTrue(String sourceBoardId);

    /**
     * Close out the tickets a sync did not see.
     *
     * <p>monday does not report that an item left a group, so absence is the only signal
     * and it has to be derived by elimination: everything still marked present on this
     * board that was not in the payload has closed.
     *
     * <p>⚠️ A bulk {@code @Modifying} update rather than loading and saving each row.
     * The alternative loads every open ticket on the board into the session to flip one
     * boolean, and it must run in the <em>same</em> transaction as the upserts or a
     * failure halfway through would leave tickets marked closed that are still open.
     *
     * <p>{@code clearAutomatically} because the upsert pass has almost certainly loaded
     * these same rows: without it the session would keep serving the stale
     * {@code isPresent = true} copies for the rest of the transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE CaseTicket t
              SET t.isPresent = false,
                  t.lastSyncedAt = :syncedAt
            WHERE t.source = :source
              AND t.sourceBoardId = :boardId
              AND t.isPresent = true
              AND t.sourceItemId NOT IN :seenItemIds
           """)
    int markAbsent(@Param("source") CaseSource source,
                   @Param("boardId") String boardId,
                   @Param("seenItemIds") Collection<String> seenItemIds,
                   @Param("syncedAt") LocalDateTime syncedAt);

    /**
     * The same, for a board that came back empty.
     *
     * <p>Separate because {@code NOT IN ()} with an empty collection is not valid SQL —
     * Hibernate renders it in a way Postgres rejects, so the query above cannot simply
     * be called with an empty list.
     *
     * <p>⚠️ Callers must be sure the board is genuinely empty rather than unreadable.
     * {@code MondayBoardReader} already turns an empty {@code boards} array into an
     * explicit "not visible to this token" error for exactly this reason: a revoked
     * token would otherwise close every open case on the board.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE CaseTicket t
              SET t.isPresent = false,
                  t.lastSyncedAt = :syncedAt
            WHERE t.source = :source
              AND t.sourceBoardId = :boardId
              AND t.isPresent = true
           """)
    int markAllAbsent(@Param("source") CaseSource source,
                      @Param("boardId") String boardId,
                      @Param("syncedAt") LocalDateTime syncedAt);
}
