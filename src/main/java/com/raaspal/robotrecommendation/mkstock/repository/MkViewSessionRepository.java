package com.raaspal.robotrecommendation.mkstock.repository;

import com.raaspal.robotrecommendation.mkstock.entity.MkViewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MkViewSessionRepository extends JpaRepository<MkViewSession, UUID> {

    Optional<MkViewSession> findByTokenHash(String tokenHash);

    long countByPinIdAndExpiresAtAfter(UUID pinId, Instant now);

    @Modifying
    @Query("delete from MkViewSession s where s.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from MkViewSession s")
    int deleteAllSessions();
}
