package com.raaspal.robotrecommendation.casereport.aotsheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The step of an AOTGA case the sheet does not hold: the old part, back from AOT, claimed
 * from the manufacturer (V66). One row per AOT ticket, made when the sync sees the row turn
 * blue, or when somebody records a claim for a row the tracker found already blue.
 */
@Entity
@Table(name = "aotga_claim")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AotgaClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "spreadsheet_id", nullable = false, columnDefinition = "TEXT")
    private String spreadsheetId;

    /** The sheet's own case id: AOT's ticket number. */
    @Column(name = "ticket_no", nullable = false, columnDefinition = "TEXT")
    private String ticketNo;

    /** The day the sync first saw the row blue; null when it was blue before that. */
    @Column(name = "old_part_back_on")
    private LocalDate oldPartBackOn;

    @Column(name = "claimed_on")
    private LocalDate claimedOn;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "claimed_by", columnDefinition = "TEXT")
    private String claimedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
