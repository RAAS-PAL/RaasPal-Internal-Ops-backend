-- V72 - Which of a PM visit's dates a logged change moved.
--
-- From 2026-10-08 the planner never moves a visit's Plan date (user): moves, and dates
-- given to visits with none, write its Action date. Moves logged before then changed the
-- Plan date, so an undo of one must put the Plan date back: the rows already here are PLAN.
-- old_plan_date / new_plan_date hold the dates of whichever field the row names.
ALTER TABLE pm_plan_change ADD COLUMN date_field VARCHAR(8) NOT NULL DEFAULT 'PLAN';
