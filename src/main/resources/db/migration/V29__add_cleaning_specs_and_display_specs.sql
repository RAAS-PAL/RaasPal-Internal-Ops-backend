-- =============================================================================
-- V29 - robot_specs_cleaning (AI) + robot_display_specs (RIMS)
--
-- Two spec tables, two audiences, deliberately unrelated:
--
--   robot_specs_cleaning  typed columns the AI COMPARES against customer
--                         requirements. Shape follows the source datasheet
--                         (docs/DataSheetGS_filled_07152026.xlsx, 103 columns).
--
--   robot_display_specs   curated label/value rows RIMS DISPLAYS. Admin picks
--                         what appears, per model, so a robot can show 5 specs
--                         and another 12.
--
-- The old `robot_specs` table is NOT touched here. This is expand/contract - the
-- recommendation engine keeps running on the old table until it is verified
-- against the new one, then a later migration drops it. Altering a live table
-- that RecommendationService, RobotImportService, its DTOs and 95 tests depend
-- on would break all of them in one step with no way back.
--
-- Naming follows V2's convention: snake_case with a unit suffix. Source-sheet
-- typos are corrected here ("Vacumm" -> vacuum, "ioT Intregation" -> iot_integration).
-- N/A in the sheet imports as NULL.
-- =============================================================================


CREATE TABLE robot_specs_cleaning (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    robot_id UUID NOT NULL UNIQUE REFERENCES robots(id) ON DELETE CASCADE,

    -- ── Physical ─────────────────────────────────────────────────────────────
    dimension_l_mm                  INTEGER,
    dimension_w_mm                  INTEGER,
    dimension_h_mm                  INTEGER,
    robot_weight_kg                 DECIMAL(8,2),

    -- ── Cleaning widths ──────────────────────────────────────────────────────
    -- Replaces the single width_cleaning_mm of the old table: the datasheet
    -- gives a separate width per mode, and they differ (Omnie Disc: sweep N/A,
    -- scrub 520, mop 715).
    sweep_width_mm                  INTEGER,
    scrub_width_mm                  INTEGER,
    mop_width_mm                    INTEGER,

    -- ── Brush & vacuum ───────────────────────────────────────────────────────
    brush_pressure_kg               DECIMAL(6,2),
    roller_speed_rpm                INTEGER,
    brush_speed_rpm                 INTEGER,
    vacuum_pressure_kpa             DECIMAL(6,2),
    vibration_more_than             DECIMAL(6,2),   -- sheet gives no unit
    noise_level_db                  DECIMAL(5,1),

    -- ── Speed ────────────────────────────────────────────────────────────────
    max_travelling_speed_ms         DECIMAL(5,2),
    max_operation_speed_ms          DECIMAL(5,2),

    -- ── Efficiency (m2/h) ────────────────────────────────────────────────────
    sweep_efficiency_sqm_h          INTEGER,
    scrub_efficiency_sqm_h          INTEGER,
    mop_efficiency_sqm_h            INTEGER,
    sweep_scrub_efficiency_sqm_h    INTEGER,
    vacuum_efficiency_sqm_h         INTEGER,

    -- ── Capacity ─────────────────────────────────────────────────────────────
    cleaning_capacity_l             DECIMAL(6,2),
    waste_capacity_l                DECIMAL(6,2),
    trash_capacity_l                DECIMAL(6,2),
    dust_bag_capacity_l             DECIMAL(6,2),
    dust_bag_charging_dock          BOOLEAN,
    auto_clean                      BOOLEAN,

    -- ── Power ────────────────────────────────────────────────────────────────
    max_output_power_w              INTEGER,
    drive_motor_power_w             INTEGER,

    -- ── Battery ──────────────────────────────────────────────────────────────
    battery_type                    VARCHAR(64),    -- "Lithium Iron Phosphate" | "Lithium-Ion Polymer"
    battery_voltage_v               DECIMAL(6,1),
    battery_capacity_ah             DECIMAL(8,2),
    battery_charging_time_hr        DECIMAL(5,2),
    sweep_work_time_hr              DECIMAL(5,2),
    scrub_work_time_hr              DECIMAL(5,2),
    mop_work_time_hr                DECIMAL(5,2),

    -- ── Access & obstacles ───────────────────────────────────────────────────
    min_pass_width_mm               INTEGER,
    min_pass_height_mm              INTEGER,
    max_vertical_obstacle_height_mm INTEGER,
    max_trench_clearance_mm         INTEGER,
    max_narrow_mm                   INTEGER,
    min_u_turn_width_mm             INTEGER,
    min_edge_from_wall_mm           INTEGER,
    max_step_height_mm              INTEGER,
    slope_angle_auto_deg            DECIMAL(5,1),
    sensor_distance_m               DECIMAL(6,2),   -- 25-150 m; LiDAR range, not a unit error
    sensor_degree_deg               DECIMAL(5,1),

    -- ── Environment ──────────────────────────────────────────────────────────
    ip_rating                       VARCHAR(20),    -- "IPX3" | "IP54"
    hepa                            VARCHAR(20),    -- "H13"; TEXT, not boolean as in the old table
    min_operating_temp_c            DECIMAL(5,1),
    max_operating_temp_c            DECIMAL(5,1),
    min_operating_humidity_pct      DECIMAL(5,1),
    max_operating_humidity_pct      DECIMAL(5,1),
    -- Constant 1 across all 12 datasheet rows, so it carries no signal today.
    -- Kept for fidelity with the source sheet; revisit if it gains real values.
    application                     BOOLEAN,
    outdoor                         BOOLEAN,
    indoor                          BOOLEAN,

    -- ── Cleaning functions ───────────────────────────────────────────────────
    fn_sweep_no_vacuum              BOOLEAN,
    fn_sweep_vacuum                 BOOLEAN,
    fn_mop_dry                      BOOLEAN,
    fn_mop_wet                      BOOLEAN,
    fn_scrub_brush_roller           BOOLEAN,
    fn_scrub_brush_disc             BOOLEAN,

    -- ── Sensors & navigation ─────────────────────────────────────────────────
    nav_2d_lidar                    BOOLEAN,
    nav_3d_lidar                    BOOLEAN,
    nav_vslam                       BOOLEAN,
    sensor_rgb                      BOOLEAN,
    sensor_rgbd                     BOOLEAN,
    sensor_ultrasonic               BOOLEAN,
    anti_collision                  BOOLEAN,
    anti_drop                       BOOLEAN,
    spot_ai                         BOOLEAN,

    -- ── Features ─────────────────────────────────────────────────────────────
    manual_drive                    BOOLEAN,
    multi_robot_connect             BOOLEAN,
    auto_task_switch                BOOLEAN,
    iot_integration                 BOOLEAN,
    work_station                    BOOLEAN,
    dock_charge                     BOOLEAN,
    manual_charge                   BOOLEAN,

    -- ── Floor suitability ────────────────────────────────────────────────────
    -- Replaces the old layout_2x2..layout_20x20 tile-size booleans.
    -- Constant 0 across the datasheet; typed boolean to match the source.
    floor_layout_method             BOOLEAN,
    floor_rough_surface             BOOLEAN,
    floor_smooth_surface            BOOLEAN,
    floor_real_wood                 BOOLEAN,
    floor_paving_blocks             BOOLEAN,
    floor_granite_tiles             BOOLEAN,
    floor_ceramic_tiles             BOOLEAN,
    floor_marble                    BOOLEAN,
    floor_terrazzo                  BOOLEAN,
    floor_nature_stone              BOOLEAN,
    floor_terracotta                BOOLEAN,
    floor_smooth_concrete           BOOLEAN,
    floor_coarse_concrete           BOOLEAN,
    floor_stamped_concrete          BOOLEAN,
    floor_epoxy                     BOOLEAN,
    floor_pu                        BOOLEAN,
    floor_asphalt                   BOOLEAN,
    floor_short_carpet              BOOLEAN,
    floor_long_carpet               BOOLEAN,
    floor_spc                       BOOLEAN,
    floor_wpc                       BOOLEAN,
    floor_laminate                  BOOLEAN,
    floor_vinyl_pvc                 BOOLEAN,
    floor_washed_sand               BOOLEAN,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);


-- ─────────────────────────────────────────────────────────────────────────────
-- RIMS display specs
--
-- One row per robot model, holding an ORDERED JSON array the admin edits:
--   [{"label": "Cleaning Width", "value": "700", "unit": "mm"}, ...]
--
-- JSONB rather than a row per spec line because this data is read whole, written
-- whole, never queried into, and array position IS display order - free in JSON,
-- a maintained display_order column in SQL.
--
-- The trade: no database-level guarantees. Duplicate labels and oversized arrays
-- are rejected in the service layer (Bean Validation on the DisplaySpec record)
-- rather than by a UNIQUE constraint.
--
-- robot_id doubles as the primary key, so one row per robot is structural.
--
-- Deliberately owned by RIMS: it never reads robot_specs_cleaning, so the AI's
-- spec columns can be renamed, split per robot type, or extended freely without
-- RIMS noticing. The only contract is {label, value, unit}.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE robot_display_specs (
    robot_id   UUID PRIMARY KEY REFERENCES robots(id) ON DELETE CASCADE,
    specs      JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);


-- NOTE: the 12 catalogue rows from the datasheet are deliberately NOT inserted
-- here. Several would near-duplicate existing rows ("Omnie Disc Brush" vs the
-- existing "Omnie"), and whether to merge or keep both is a human decision.
-- They arrive through the importer, where they can be reviewed. New models must
-- be created with test_status = 'DRAFT' so RecommendationService - which filters
-- on VERIFIED - never recommends a model that is only stocked, not sold.
