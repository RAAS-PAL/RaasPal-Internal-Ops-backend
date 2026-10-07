package com.raaspal.robotrecommendation.casereport.aotsheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** AOTGA's list as it stood at the end of one day (V67). Today's is rewritten as it changes. */
@Entity
@Table(name = "aotga_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AotgaSnapshot {

    @Id
    @Column(name = "run_date")
    private LocalDate runDate;

    @Column(name = "spreadsheet_id", columnDefinition = "TEXT")
    private String spreadsheetId;

    @Column(name = "view_json", nullable = false, columnDefinition = "TEXT")
    private String viewJson;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
