-- V4: Drop tables that belong to future phases (scoring engine, reports, audit log).
-- These features are excluded from MVP scope per CLAUDE.md.

DROP INDEX IF EXISTS idx_scores_recommendation_item;
DROP INDEX IF EXISTS idx_reports_recommendation_id;
DROP INDEX IF EXISTS idx_audit_logs_entity;
DROP INDEX IF EXISTS idx_audit_logs_actor;

DROP TABLE IF EXISTS scores;
DROP TABLE IF EXISTS reports;
DROP TABLE IF EXISTS audit_logs;
