ALTER TABLE job_analyses ADD COLUMN target_role VARCHAR(120);
CREATE INDEX idx_job_analyses_user_role_source ON job_analyses(user_id, target_role, source_hash, profile_hash);
