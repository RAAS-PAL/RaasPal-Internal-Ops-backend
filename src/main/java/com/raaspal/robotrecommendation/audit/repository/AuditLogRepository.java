package com.raaspal.robotrecommendation.audit.repository;

import com.raaspal.robotrecommendation.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByEntityTypeAndEntityId(String entityType, UUID entityId, Pageable pageable);

    Page<AuditLog> findByActorId(UUID actorId, Pageable pageable);
}