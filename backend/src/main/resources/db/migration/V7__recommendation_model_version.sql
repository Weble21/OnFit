-- Old rule-only snapshots remain reusable. A different model/calibration produces a new snapshot.
ALTER TABLE recommendations ADD COLUMN model_version VARCHAR(200) NOT NULL DEFAULT 'rules-only-v1';
