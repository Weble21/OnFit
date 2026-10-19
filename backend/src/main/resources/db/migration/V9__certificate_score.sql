-- Score or grade of a certificate, e.g. "900", "IH", "최종합격". Free text; existing rows stay NULL.
ALTER TABLE certificates ADD COLUMN score VARCHAR(50);
