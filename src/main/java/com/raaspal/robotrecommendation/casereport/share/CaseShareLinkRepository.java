package com.raaspal.robotrecommendation.casereport.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseShareLinkRepository extends JpaRepository<CaseShareLink, UUID> {

    Optional<CaseShareLink> findByToken(String token);

    /** The links of one kind that still work, newest first; few enough to narrow in memory. */
    @Query("SELECT l FROM CaseShareLink l WHERE l.kind = :kind AND l.revokedAt IS NULL "
            + "AND l.expiresAt > :now ORDER BY l.createdAt DESC")
    List<CaseShareLink> findActive(@Param("kind") String kind, @Param("now") OffsetDateTime now);

    /** One more opening, counted in the database so two at once both count. */
    @Modifying
    @Query("UPDATE CaseShareLink l SET l.viewCount = l.viewCount + 1, l.lastViewedAt = :at WHERE l.id = :id")
    void countView(@Param("id") UUID id, @Param("at") OffsetDateTime at);
}
