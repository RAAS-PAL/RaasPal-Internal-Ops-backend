-- =============================================================================
-- V13 - Telemetry: add operator column to robot_task_reports
-- Gausium's V2 task report includes the name of the operator who ran the
-- cleaning task; stored alongside the other Gausium-specific fields for
-- potential future use even though it is not part of the monthly report.
-- =============================================================================

ALTER TABLE robot_task_reports ADD COLUMN operator VARCHAR(255);
