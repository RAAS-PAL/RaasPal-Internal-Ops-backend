-- ─────────────────────────────────────────────────────────────────────────────
-- One-time backfill: create the PCS partner and tag the deployments of the
-- robots PCS services. Run AFTER V22 has been applied (deploy first).
--
-- HOW TO USE
--   1. Fill in the serial numbers of the robots PCS services (bottom).
--   2. Run the whole script in one session (psql / Supabase SQL editor).
--   3. Verify with the SELECT at the end.
--
-- Safe to re-run: the partner insert is idempotent (unique name), and the
-- UPDATE only touches the listed serials.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1) The PCS partner (id generated once, stable on re-run thanks to ON CONFLICT)
INSERT INTO partners (id, name, is_active, created_at)
VALUES (gen_random_uuid(), 'PCS', true, now())
ON CONFLICT (name) DO NOTHING;

-- 2) Tag PCS-serviced deployments (active ones) by robot serial number.
--    >>> EDIT THE SERIAL LIST BELOW <<<
UPDATE deployments d
SET partner_id = (SELECT id FROM partners WHERE name = 'PCS')
FROM robot_units r
WHERE d.robot_unit_id = r.id
  AND d.is_active = true
  AND r.serial_number IN (
      -- 'GS100-0030-KCN-G000',
      -- 'GS101-0100-6CN-8100',
      -- ... all PCS robot serials here ...
      '__FILL_ME__'
  );

-- 2b) ALTERNATIVE to (2): if ALL PCS robots are deployed under customers named
--     with the "PCS : ..." prefix (e.g. "PCS : Makro"), tag by customer name
--     instead of listing serials. Use ONE of (2) or (2b), not both.
-- UPDATE deployments d
-- SET partner_id = (SELECT id FROM partners WHERE name = 'PCS')
-- FROM customer_profiles c
-- WHERE d.customer_profile_id = c.id
--   AND d.is_active = true
--   AND c.company_name LIKE 'PCS%';

-- 3) Verify: how many deployments are tagged to PCS, and which robots.
SELECT r.serial_number, c.company_name, d.site
FROM deployments d
JOIN robot_units r ON r.id = d.robot_unit_id
JOIN customer_profiles c ON c.id = d.customer_profile_id
WHERE d.partner_id = (SELECT id FROM partners WHERE name = 'PCS')
ORDER BY r.serial_number;
