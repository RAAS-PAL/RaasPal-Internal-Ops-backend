-- =============================================================================
-- Seed demo TASK REPORTS for an already-registered robot, so the web report
-- preview (GET /api/v1/reports/preview) shows real aggregated data.
--
-- Prereq: the robot must already exist (registered via the Robots tab) AND have
-- an active deployment to a customer. This script looks up robot_unit_id and
-- customer_profile_id by serial number, so you only set the SN + month.
--
-- Run against your DEV database (Supabase SQL Editor or psql). Re-runnable.
-- Change @SN below if your robot's serial number differs, and the dates/month
-- if you want a different reporting period.
-- =============================================================================

-- Robot serial number to attach the reports to:
--   GS208-0410-L3P-X100   (change to your registered robot's SN)

INSERT INTO robot_task_reports (
    external_task_id, robot_unit_id, customer_profile_id, brand,
    cleaning_plan, task_completion_pct, start_time, end_time,
    work_efficiency_sqm_h, working_time_seconds, cleaning_area_sqm, planned_area_sqm,
    start_battery_pct, end_battery_pct, water_consumption_l,
    brush_residual_pct, filter_residual_pct, suction_blade_residual_pct,
    map_name, cleaning_mode, task_report_png_uri, task_end_status,
    report_month, synced_at)
SELECT 'DEMO-0001', ru.id, d.customer_profile_id, ru.brand,
       'Zone-1', 86.60, '2026-06-03T01:00:00Z', '2026-06-03T01:14:56Z',
       626.77, 727, 126.67, 146.31, 65, 59, 2.29, 99.92, 100.00, 100.00,
       'Floor-8', 'Floor Washing', 'https://example.com/r1.png', 0, '2026-06', NOW()
FROM robot_units ru
JOIN deployments d ON d.robot_unit_id = ru.id AND d.is_active = TRUE
WHERE ru.serial_number = 'GS208-0410-L3P-X100'
ON CONFLICT (external_task_id) DO NOTHING;

INSERT INTO robot_task_reports (
    external_task_id, robot_unit_id, customer_profile_id, brand,
    cleaning_plan, task_completion_pct, start_time, end_time,
    work_efficiency_sqm_h, working_time_seconds, cleaning_area_sqm, planned_area_sqm,
    start_battery_pct, end_battery_pct, water_consumption_l,
    brush_residual_pct, filter_residual_pct, suction_blade_residual_pct,
    map_name, cleaning_mode, task_report_png_uri, task_end_status,
    report_month, synced_at)
SELECT 'DEMO-0002', ru.id, d.customer_profile_id, ru.brand,
       'Zone-2', 92.10, '2026-06-10T02:00:00Z', '2026-06-10T02:20:30Z',
       540.20, 1230, 184.50, 200.30, 80, 71, 3.10, 98.40, 99.50, 99.00,
       'Floor-8', 'Floor Washing', 'https://example.com/r2.png', 0, '2026-06', NOW()
FROM robot_units ru
JOIN deployments d ON d.robot_unit_id = ru.id AND d.is_active = TRUE
WHERE ru.serial_number = 'GS208-0410-L3P-X100'
ON CONFLICT (external_task_id) DO NOTHING;

INSERT INTO robot_task_reports (
    external_task_id, robot_unit_id, customer_profile_id, brand,
    cleaning_plan, task_completion_pct, start_time, end_time,
    work_efficiency_sqm_h, working_time_seconds, cleaning_area_sqm, planned_area_sqm,
    start_battery_pct, end_battery_pct, water_consumption_l,
    brush_residual_pct, filter_residual_pct, suction_blade_residual_pct,
    map_name, cleaning_mode, task_report_png_uri, task_end_status,
    report_month, synced_at)
SELECT 'DEMO-0003', ru.id, d.customer_profile_id, ru.brand,
       'Zone-1', 78.30, '2026-06-21T01:30:00Z', '2026-06-21T01:40:10Z',
       410.00, 610, 70.40, 90.00, 55, 49, 1.80, 97.10, 98.20, 97.80,
       'Floor-8', 'Floor Washing', 'https://example.com/r3.png', 0, '2026-06', NOW()
FROM robot_units ru
JOIN deployments d ON d.robot_unit_id = ru.id AND d.is_active = TRUE
WHERE ru.serial_number = 'GS208-0410-L3P-X100'
ON CONFLICT (external_task_id) DO NOTHING;

-- Cleanup: DELETE FROM robot_task_reports WHERE external_task_id LIKE 'DEMO-%';
