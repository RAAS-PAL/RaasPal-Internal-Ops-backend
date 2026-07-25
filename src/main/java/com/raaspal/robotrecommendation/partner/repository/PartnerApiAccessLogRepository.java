package com.raaspal.robotrecommendation.partner.repository;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface PartnerApiAccessLogRepository extends JpaRepository<PartnerApiAccessLog, UUID> {

    /** One partner's recent requests, newest first. */
    Page<PartnerApiAccessLog> findByPartnerIdOrderByRequestedAtDesc(UUID partnerId, Pageable pageable);

    /**
     * Deletes audit rows older than the given cut-off. The table grows with every
     * request, so retention is enforced rather than assumed.
     */
    @Modifying
    @Query("delete from PartnerApiAccessLog l where l.requestedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
