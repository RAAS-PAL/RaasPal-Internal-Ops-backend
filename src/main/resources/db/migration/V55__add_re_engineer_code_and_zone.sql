-- RE assignment: employee codes, a home zone for engineers based outside Bangkok, and the
-- 2026-09-22 roster.
--
-- Additive, plus a one-off data update of re_engineer.

ALTER TABLE re_engineer ADD COLUMN employee_code TEXT;
CREATE UNIQUE INDEX ux_re_engineer_employee_code ON re_engineer (employee_code) WHERE employee_code IS NOT NULL;

-- A zone code from app.re-assignment.zones (e.g. EASTERN_SEABOARD). Null = works from
-- Bangkok and goes anywhere. An engineer with a zone is preferred for tickets there and is
-- the last resort elsewhere.
ALTER TABLE re_engineer ADD COLUMN home_zone TEXT;

-- ─── Roster, 2026-09-22 ──────────────────────────────────────────────────────
-- The eight REs the team assigns work to, with their English nicknames. Rows came from the
-- skill-matrix import, which stored the Thai nickname; each is matched by that nickname
-- (or by the English one, if it was already changed in the console) and only when exactly
-- one engineer carries it, so a surprise never lands on the wrong person.

CREATE TEMPORARY TABLE re_roster (code TEXT, nick_th TEXT, nick_en TEXT, zone TEXT);
INSERT INTO re_roster VALUES
    ('RAAS-00119', 'เจ',    'Jay',    NULL),
    ('RAAS-00156', 'ไผ่',   'Phai',   NULL),
    ('RAAS-00166', 'แพน',   'Pan',    NULL),
    ('RAAS-00173', 'คิว',   'Que',    NULL),
    ('RAAS-00013', 'ปู',    'Pu',     NULL),
    ('RAAS-00212', 'ไบร์ท', 'Bright', NULL),
    ('RAAS-00211', 'ต้น',   'Ton',    NULL),
    ('RAAS-00200', 'ภูมิ',  'Poom',   'EASTERN_SEABOARD');

UPDATE re_engineer e
   SET employee_code = r.code, nickname = r.nick_en, home_zone = r.zone, updated_at = now()
  FROM re_roster r
 WHERE trim(e.nickname) IN (r.nick_th, r.nick_en)
   AND (SELECT count(*) FROM re_engineer x WHERE trim(x.nickname) IN (r.nick_th, r.nick_en)) = 1;

-- Everyone else leaves the rotation: deactivated, not deleted - their history stays.
-- Only when the whole roster was found, so a partial match deactivates nobody.
UPDATE re_engineer
   SET active = false, updated_at = now()
 WHERE employee_code IS NULL
   AND active
   AND (SELECT count(*) FROM re_engineer WHERE employee_code IS NOT NULL) = (SELECT count(*) FROM re_roster);

DROP TABLE re_roster;
