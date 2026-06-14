-- =============================================================================
-- V14 - Telemetry: widen residual percentage columns to DECIMAL(5,2)
-- Gausium's consumablesResidualPercentage values can include 2 decimal
-- places (e.g. 99.92), but these columns were INT, which would reject real
-- API responses during JSON deserialization. Matches the precision shown in
-- the reference monthly report (docs/Cleaning plan_*.xlsx).
-- =============================================================================

ALTER TABLE robot_task_reports ALTER COLUMN brush_residual_pct TYPE DECIMAL(5,2);
ALTER TABLE robot_task_reports ALTER COLUMN filter_residual_pct TYPE DECIMAL(5,2);
ALTER TABLE robot_task_reports ALTER COLUMN suction_blade_residual_pct TYPE DECIMAL(5,2);
