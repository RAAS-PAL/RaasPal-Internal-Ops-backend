package com.raaspal.robotrecommendation.pm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One customer site under a PM agreement - a parent item on the PM Cleaning or
 * PM Delivery board.
 *
 * <p>This is a mirror, not a master. monday owns these fields; nothing here is
 * edited by the planner and nothing is written back. A row survives its source
 * item being deleted: the sync marks it {@code isPresent = false} instead of
 * removing it, so a visit history does not vanish because somebody tidied a board.
 *
 * <p>Note what the source boards do NOT have: a PM frequency column, a last-PM
 * column and a next-PM-due column. The forward schedule is kept as dated
 * subitems ({@link PmVisit}) instead, so the planner reads dates that already
 * exist rather than calculating them.
 */
@Entity
@Table(name = "pm_contract")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PmContract implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "source_board_id", nullable = false, length = 32)
    private String sourceBoardId;

    /** monday item id. Unique with the board id; the key every sync upserts on. */
    @Column(name = "source_item_id", nullable = false, length = 32)
    private String sourceItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_line", nullable = false, length = 16)
    private PmServiceLine serviceLine;

    @Column(name = "item_name", columnDefinition = "TEXT")
    private String itemName;

    /** monday group, e.g. "PM Makro 72 สาขา" or "หมดสัญญา" (contract ended). */
    @Column(name = "group_title", columnDefinition = "TEXT")
    private String groupTitle;

    @Column(name = "project_raw", columnDefinition = "TEXT")
    private String projectRaw;

    @Column(name = "customer_name_raw", columnDefinition = "TEXT")
    private String customerNameRaw;

    @Column(name = "province_raw", columnDefinition = "TEXT")
    private String provinceRaw;

    /** Kept only so a bad mapping can be diagnosed; grouping never reads it. */
    @Column(name = "region_raw", columnDefinition = "TEXT")
    private String regionRaw;

    @Column(name = "district_raw", columnDefinition = "TEXT")
    private String districtRaw;

    /** Canonical province from {@code ProvinceResolver}, or UNASSIGNED. */
    @Column(name = "province_resolved", length = 64)
    private String provinceResolved;

    @Column(name = "region_resolved", length = 32)
    private String regionResolved;

    @Column(name = "zone_resolved", length = 32)
    private String zoneResolved;

    /** Populated on a minority of contracts, which is why there is no map yet. */
    @Column(name = "lat", precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(name = "lng", precision = 10, scale = 7)
    private BigDecimal lng;

    @Column(name = "contract_type", columnDefinition = "TEXT")
    private String contractType;

    @Column(name = "warranty_text", columnDefinition = "TEXT")
    private String warrantyText;

    @Column(name = "warranty_start")
    private LocalDate warrantyStart;

    @Column(name = "warranty_end")
    private LocalDate warrantyEnd;

    /**
     * Chain the site belongs to ("Makro", "BBQ", "PCS"), derived from the item name.
     *
     * <p>Neither board carries a company column - Project is empty on 44% of
     * contracts and the customer-name cell on 64% - so grouping by either would
     * drop most of the estate into "unknown". The derivation is in
     * {@code PmItemMapper.deriveCompany}, and it is stored rather than computed per
     * request so the filter can be indexed and so a wrong grouping is visible in
     * the data instead of hidden in a query.
     */
    @Column(name = "company", length = 128)
    private String company;

    @Column(name = "robot_model", columnDefinition = "TEXT")
    private String robotModel;

    /** Comma-joined serials; the boards spread them over up to five columns. */
    @Column(name = "robot_serials", columnDefinition = "TEXT")
    private String robotSerials;

    @Column(name = "robot_count")
    private Integer robotCount;

    /** Every requested cell as JSON, so a new field needs no re-sync to investigate. */
    @Column(name = "raw_columns", columnDefinition = "TEXT")
    private String rawColumns;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_synced_at", nullable = false)
    private OffsetDateTime lastSyncedAt;

    /** False once the item stops coming back from monday. */
    @Column(name = "is_present", nullable = false)
    private boolean isPresent;

    /**
     * Whether this row still has to be INSERTed.
     *
     * <p>Ids are assigned by the mapper rather than generated by the database,
     * because a visit has to reference its contract's id before either is flushed.
     * Spring Data's default rule is "id is null, therefore new", which with an
     * assigned id would make every insert arrive as a merge - an extra SELECT per
     * row, and a clash with Hibernate's own unsaved-value detection. Implementing
     * Persistable states it outright instead.
     */
    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Anything loaded or just written is, by definition, no longer new. */
    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }
}
