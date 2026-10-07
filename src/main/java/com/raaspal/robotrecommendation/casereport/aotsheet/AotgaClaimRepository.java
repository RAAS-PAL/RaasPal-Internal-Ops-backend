package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AotgaClaimRepository extends JpaRepository<AotgaClaim, UUID> {

    List<AotgaClaim> findBySpreadsheetId(String spreadsheetId);

    Optional<AotgaClaim> findBySpreadsheetIdAndTicketNo(String spreadsheetId, String ticketNo);
}
