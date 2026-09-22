-- RE assignment: remove the engineers who are not on the 2026-09-22 roster.
--
-- V55 deactivated them; the team asked for them to be gone entirely. These are the
-- engineers the skill-matrix import created who carry no employee code, and only once the
-- whole 8-person roster is in place - otherwise nothing is removed.
--
-- Their skill-change history sits behind the append-only trigger from V53; it is switched
-- off for this one delete and back on straight after. re_event keeps its audit rows (it has
-- no foreign key to re_engineer).

CREATE TEMPORARY TABLE re_gone AS
    SELECT id FROM re_engineer
     WHERE employee_code IS NULL
       AND NOT active
       AND (SELECT count(*) FROM re_engineer WHERE employee_code IS NOT NULL) = 8;

DELETE FROM re_schedule    WHERE engineer_id IN (SELECT id FROM re_gone);
DELETE FROM re_leave       WHERE engineer_id IN (SELECT id FROM re_gone);
DELETE FROM re_skill_level WHERE engineer_id IN (SELECT id FROM re_gone);

ALTER TABLE re_skill_change DISABLE TRIGGER re_skill_change_append_only;
DELETE FROM re_skill_change WHERE engineer_id IN (SELECT id FROM re_gone);
ALTER TABLE re_skill_change ENABLE TRIGGER re_skill_change_append_only;

DELETE FROM re_schedule    WHERE assignment_id IN (SELECT id FROM re_assignment WHERE engineer_id IN (SELECT id FROM re_gone));
DELETE FROM re_assignment  WHERE engineer_id IN (SELECT id FROM re_gone);
DELETE FROM re_engineer    WHERE id IN (SELECT id FROM re_gone);

DROP TABLE re_gone;
