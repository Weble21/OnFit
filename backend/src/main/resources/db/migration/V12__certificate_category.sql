-- Certificates are grouped by kind (language, technical, other) instead of by issuer.
-- Existing rows become OTHER. Issuer values are dropped on purpose (decided 2026-10-12).
ALTER TABLE certificates ADD COLUMN category VARCHAR(20) DEFAULT 'OTHER' NOT NULL;
ALTER TABLE certificates ADD CONSTRAINT certificates_category_check CHECK (category IN ('LANGUAGE', 'TECHNICAL', 'OTHER'));
ALTER TABLE certificates DROP COLUMN issuer;
