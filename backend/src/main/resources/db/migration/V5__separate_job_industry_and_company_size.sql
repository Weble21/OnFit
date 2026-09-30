-- Existing company_type values represent industries after V4. Preserve them without guessing company size.
ALTER TABLE job_postings RENAME COLUMN company_type TO industry;
ALTER TABLE job_postings ADD COLUMN company_size VARCHAR(100);
