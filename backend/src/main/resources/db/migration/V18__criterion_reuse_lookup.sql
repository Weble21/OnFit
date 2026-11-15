-- Reuse validated criterion decisions across postings for the same user/profile/policy.
CREATE INDEX idx_job_analyses_criterion_reuse
    ON job_analyses(user_id, profile_hash, calculator_version, created_at);
