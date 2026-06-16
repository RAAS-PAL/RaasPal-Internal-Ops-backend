-- =============================================================================
-- Seed data for INTERNAL testing of the monthly report delivery feature.
--
-- This is NOT a Flyway migration (it lives in docs/, not db/migration/) so it
-- never runs automatically against production. Run it by hand against your DEV
-- database only — e.g. paste into the Supabase SQL Editor, or:
--     psql "<your-dev-connection-string>" -f docs/seed-test-monthly-report.sql
--
-- It creates one test customer (a stand-in for your own team), one robot, an
-- active deployment, and 3 cleaning task reports for month 2026-05 so you can
-- immediately run:
--     POST /api/v1/reports/monthly/run?month=2026-05&testMode=true
-- and get back a signed Supabase download URL to verify the generated Excel.
--
-- For the LINE step (testMode=false), replace 'REPLACE_WITH_TEAM_LINE_ID' below
-- with your team's LINE group id (or a teammate's user id) captured by the n8n
-- webhook, then re-run this script (it upserts).
--
-- Re-runnable: every row upserts on a natural key. To remove the fixture, run
-- the cleanup block at the bottom (commented out).
-- =============================================================================

-- 1. Test user (owner of the test customer profile) ---------------------------
INSERT INTO users (id, email, password, full_name, role, is_active)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'monthly-report-test@raaspal.local',
    -- bcrypt hash of 'password' (login not needed for the report test, but the
    -- column is NOT NULL); change/disable later if you don't want it usable.
    '$2a$10$7EqJtq98hPqEX7fNZaFWoO1Y6mF6e0bQ6m6cQ8Qz8Qz8Qz8Qz8Qz',
    'Monthly Report Test',
    'CUSTOMER',
    TRUE
)
ON CONFLICT (email) DO NOTHING;

-- 2. Test customer profile ----------------------------------------------------
INSERT INTO customer_profiles (id, user_id, company_name, industry, contact_phone, line_user_id)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'RAASPAL Internal Test Co.',
    'Healthcare',
    '+66000000000',
    'REPLACE_WITH_TEAM_LINE_ID'      -- team LINE group id or teammate user id
)
ON CONFLICT (user_id) DO UPDATE
    SET company_name = EXCLUDED.company_name,
        line_user_id = EXCLUDED.line_user_id;

-- 3. Test robot unit ----------------------------------------------------------
INSERT INTO robot_units (id, serial_number, brand, model, name)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    'GS401-TEST-0001',
    'GAUSIUM',
    'Scrubber 50 Pro',
    'Test Robot — 8th Floor'
)
ON CONFLICT (serial_number) DO NOTHING;

-- 4. Active deployment (links robot -> customer) ------------------------------
INSERT INTO deployments (id, robot_unit_id, customer_profile_id, site, is_active, deployed_at)
VALUES (
    '44444444-4444-4444-4444-444444444444',
    '33333333-3333-3333-3333-333333333333',
    '22222222-2222-2222-2222-222222222222',
    'Test Site — 8th Floor',
    TRUE,
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- 5. Three task reports for 2026-05 -------------------------------------------
INSERT INTO robot_task_reports (
    external_task_id, robot_unit_id, customer_profile_id, brand,
    cleaning_plan, task_completion_pct, start_time, end_time,
    work_efficiency_sqm_h, working_time_seconds, cleaning_area_sqm, planned_area_sqm,
    start_battery_pct, end_battery_pct, water_consumption_l,
    brush_residual_pct, filter_residual_pct, suction_blade_residual_pct,
    map_name, cleaning_mode, task_report_png_uri,
    report_month, synced_at
) VALUES
    ('TEST-TASK-0001', '33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'GAUSIUM',
     'Zone-1', 86.60, '2026-05-03T01:00:00Z', '2026-05-03T01:14:56Z',
     626.77, 727, 126.67, 146.31,
     65, 59, 2.29,
     99.92, 100.00, 100.00,
     'MCR_Floor-8', 'Floor Washing', 'https://example.com/report1.png',
     '2026-05', NOW()),
    ('TEST-TASK-0002', '33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'GAUSIUM',
     'Zone-2', 92.10, '2026-05-10T02:00:00Z', '2026-05-10T02:20:30Z',
     540.20, 1230, 184.50, 200.30,
     80, 71, 3.10,
     98.40, 99.50, 99.00,
     'MCR_Floor-8', 'Floor Washing', 'https://example.com/report2.png',
     '2026-05', NOW()),
    ('TEST-TASK-0003', '33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'GAUSIUM',
     'Zone-1', 78.30, '2026-05-21T01:30:00Z', '2026-05-21T01:40:10Z',
     410.00, 610, 70.40, 90.00,
     55, 49, 1.80,
     97.10, 98.20, 97.80,
     'MCR_Floor-8', 'Floor Washing', 'https://example.com/report3.png',
     '2026-05', NOW())
ON CONFLICT (external_task_id) DO NOTHING;

-- =============================================================================
-- CLEANUP (uncomment and run to remove the fixture)
-- =============================================================================
-- DELETE FROM robot_task_reports WHERE external_task_id LIKE 'TEST-TASK-%';
-- DELETE FROM deployments        WHERE id = '44444444-4444-4444-4444-444444444444';
-- DELETE FROM robot_units        WHERE serial_number = 'GS401-TEST-0001';
-- DELETE FROM customer_profiles  WHERE id = '22222222-2222-2222-2222-222222222222';
-- DELETE FROM users              WHERE email = 'monthly-report-test@raaspal.local';
