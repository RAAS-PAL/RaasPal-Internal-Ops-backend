-- Align robot_specs with the real Robot Datasheet CSV topics.
-- V1 used architecture-oriented names; this migration makes the persisted model
-- follow the source spreadsheet so import mapping is direct.

ALTER TABLE robot_specs RENAME COLUMN weight_kg TO robot_weight_kg;
ALTER TABLE robot_specs RENAME COLUMN cleaning_width_mm TO width_cleaning_mm;
ALTER TABLE robot_specs RENAME COLUMN noise_db TO noise_level_db;

ALTER TABLE robot_specs RENAME COLUMN func_sweep TO cleaning_function_sweep_no_vacuum;
ALTER TABLE robot_specs RENAME COLUMN func_sweep_vacuum TO cleaning_function_sweep_vacuum;
ALTER TABLE robot_specs RENAME COLUMN func_dry_mop TO cleaning_function_mop_dry;
ALTER TABLE robot_specs RENAME COLUMN func_wet_mop TO cleaning_function_mop_wet;
ALTER TABLE robot_specs RENAME COLUMN func_roller_scrub TO cleaning_function_scrub_brush_roller;
ALTER TABLE robot_specs RENAME COLUMN func_disc_scrub TO cleaning_function_scrub_brush_disc;

ALTER TABLE robot_specs RENAME COLUMN efficiency_sweep_sqm_h TO cleaning_efficiency_sweep_sqm_h;
ALTER TABLE robot_specs RENAME COLUMN efficiency_scrub_sqm_h TO cleaning_efficiency_scrub_sqm_h;
ALTER TABLE robot_specs RENAME COLUMN efficiency_mop_sqm_h TO cleaning_efficiency_mop_sqm_h;
ALTER TABLE robot_specs RENAME COLUMN efficiency_sweep_scrub_sqm_h TO cleaning_efficiency_sweep_scrub_sqm_h;
ALTER TABLE robot_specs RENAME COLUMN efficiency_vacuum_sqm_h TO cleaning_efficiency_vacuum_sqm_h;

ALTER TABLE robot_specs RENAME COLUMN tank_clean_l TO tank_capacity_clean_l;
ALTER TABLE robot_specs RENAME COLUMN tank_waste_l TO tank_capacity_waste_l;
ALTER TABLE robot_specs RENAME COLUMN tank_trash_l TO tank_capacity_trash_l;
ALTER TABLE robot_specs RENAME COLUMN dust_bag_l TO tank_capacity_dust_bag_l;

ALTER TABLE robot_specs RENAME COLUMN charging_time_hr TO battery_charging_time_hr;
ALTER TABLE robot_specs RENAME COLUMN work_time_sweep_hr TO battery_work_time_sweep_hr;
ALTER TABLE robot_specs RENAME COLUMN work_time_scrub_hr TO battery_work_time_scrub_hr;
ALTER TABLE robot_specs RENAME COLUMN work_time_sweep_vacuum_hr TO battery_work_time_sweep_vacuum_hr;
ALTER TABLE robot_specs DROP COLUMN battery_work_hr;

ALTER TABLE robot_specs RENAME COLUMN min_passable_width_mm TO minimum_passable_width_mm;
ALTER TABLE robot_specs RENAME COLUMN min_passable_height_mm TO minimum_passable_height_mm;
ALTER TABLE robot_specs RENAME COLUMN max_narrow_cross_mm TO maximum_narrow_cross_mm;
ALTER TABLE robot_specs RENAME COLUMN min_turn_width_mm TO minimum_turn_width_mm;
ALTER TABLE robot_specs RENAME COLUMN min_edge_from_wall_mm TO minimum_edge_from_wall_mm;
ALTER TABLE robot_specs RENAME COLUMN max_step_height_mm TO maximum_step_height_mm;

ALTER TABLE robot_specs RENAME COLUMN floor_paving_blocks TO floor_type_paving_blocks;
ALTER TABLE robot_specs RENAME COLUMN floor_granite TO floor_type_granite;
ALTER TABLE robot_specs RENAME COLUMN floor_marble TO floor_type_marble;
ALTER TABLE robot_specs RENAME COLUMN floor_terrazzo TO floor_type_terrazzo;
ALTER TABLE robot_specs RENAME COLUMN floor_terracotta TO floor_type_terracotta;
ALTER TABLE robot_specs RENAME COLUMN floor_ceramic TO floor_type_ceramic;
ALTER TABLE robot_specs RENAME COLUMN floor_smooth_concrete TO floor_type_smooth_concrete;
ALTER TABLE robot_specs RENAME COLUMN floor_coarse_concrete TO floor_type_coarse_concrete;
ALTER TABLE robot_specs RENAME COLUMN floor_stamped_concrete TO floor_type_stamped_concrete;
ALTER TABLE robot_specs RENAME COLUMN floor_asphalt TO floor_type_asphalt;
ALTER TABLE robot_specs RENAME COLUMN floor_epoxy TO floor_type_epoxy;
ALTER TABLE robot_specs RENAME COLUMN floor_tile TO floor_type_tile;
ALTER TABLE robot_specs RENAME COLUMN floor_short_carpet TO floor_type_short_carpet;
ALTER TABLE robot_specs RENAME COLUMN floor_long_carpet TO floor_type_long_carpet;
ALTER TABLE robot_specs RENAME COLUMN floor_spc TO floor_type_spc;
ALTER TABLE robot_specs RENAME COLUMN floor_laminate TO floor_type_laminate;
ALTER TABLE robot_specs RENAME COLUMN floor_vinyl TO floor_type_vinyl;

ALTER TABLE robot_specs RENAME COLUMN layout_2x2 TO floor_layout_2x2;
ALTER TABLE robot_specs RENAME COLUMN layout_4x4 TO floor_layout_4x4;
ALTER TABLE robot_specs RENAME COLUMN layout_8x8 TO floor_layout_8x8;
ALTER TABLE robot_specs RENAME COLUMN layout_10x10 TO floor_layout_10x10;
ALTER TABLE robot_specs RENAME COLUMN layout_12x12 TO floor_layout_12x12;
ALTER TABLE robot_specs RENAME COLUMN layout_20x20 TO floor_layout_20x20;

ALTER TABLE robot_specs RENAME COLUMN nav_lidar_2d TO navigation_lidar_2d;
ALTER TABLE robot_specs RENAME COLUMN nav_lidar_3d TO navigation_lidar_3d;
ALTER TABLE robot_specs RENAME COLUMN nav_vslam TO navigation_camera_vslam;
ALTER TABLE robot_specs RENAME COLUMN has_workstation TO work_station;
ALTER TABLE robot_specs RENAME COLUMN has_spot_ai TO spot_ai;

ALTER TABLE robot_specs ADD COLUMN outdoor_indoor VARCHAR(50);
UPDATE robot_specs
SET outdoor_indoor =
    CASE
        WHEN is_indoor IS TRUE AND is_outdoor IS TRUE THEN 'Indoor/Outdoor'
        WHEN is_indoor IS TRUE THEN 'Indoor'
        WHEN is_outdoor IS TRUE THEN 'Outdoor'
        ELSE NULL
    END;
ALTER TABLE robot_specs DROP COLUMN is_indoor;
ALTER TABLE robot_specs DROP COLUMN is_outdoor;

CREATE INDEX idx_robot_specs_outdoor_indoor ON robot_specs(outdoor_indoor);
