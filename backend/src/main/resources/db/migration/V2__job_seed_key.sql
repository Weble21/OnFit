ALTER TABLE job_postings ADD COLUMN seed_key VARCHAR(80);
CREATE UNIQUE INDEX uq_job_postings_seed_key ON job_postings(seed_key);
