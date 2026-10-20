-- Postings can also be fetched from a web address (decided 2026-10-20). The address is kept on the draft and,
-- because drafts are cleaned up after a day, on the analysis too, so a result can link back to the original.
ALTER TABLE job_analysis_drafts ADD COLUMN source_url VARCHAR(2048);
ALTER TABLE job_analyses ADD COLUMN source_url VARCHAR(2048);
ALTER TABLE job_analyses DROP CONSTRAINT ck_job_analyses_input_type;
ALTER TABLE job_analyses ADD CONSTRAINT ck_job_analyses_input_type CHECK (input_type IN ('TEXT', 'FILE', 'URL'));
