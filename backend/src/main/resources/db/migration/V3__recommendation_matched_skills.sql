-- Matched skills as structured lists, so clients no longer parse evidence sentences.
CREATE TABLE recommendation_matched_required_skills (
    recommendation_id BIGINT NOT NULL REFERENCES recommendations(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    skill_name VARCHAR(120) NOT NULL,
    PRIMARY KEY (recommendation_id, sort_order)
);

CREATE TABLE recommendation_matched_preferred_skills (
    recommendation_id BIGINT NOT NULL REFERENCES recommendations(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    skill_name VARCHAR(120) NOT NULL,
    PRIMARY KEY (recommendation_id, sort_order)
);

-- Backfill existing snapshots so they keep being reused. sort_order must stay contiguous for @OrderColumn.
-- Required matches are the posting's required skills that were not recorded as missing.
INSERT INTO recommendation_matched_required_skills (recommendation_id, sort_order, skill_name)
SELECT r.id, ROW_NUMBER() OVER (PARTITION BY r.id ORDER BY s.sort_order) - 1, s.skill_name
FROM recommendations r
JOIN job_required_skills s ON s.job_id = r.job_id
WHERE NOT EXISTS (
    SELECT 1 FROM recommendation_missing_skills m
    WHERE m.recommendation_id = r.id AND m.skill_name = s.skill_name
);

-- Preferred matches were only recorded as evidence text.
INSERT INTO recommendation_matched_preferred_skills (recommendation_id, sort_order, skill_name)
SELECT r.id, ROW_NUMBER() OVER (PARTITION BY r.id ORDER BY s.sort_order) - 1, s.skill_name
FROM recommendations r
JOIN job_preferred_skills s ON s.job_id = r.job_id
WHERE EXISTS (
    SELECT 1 FROM recommendation_evidence e
    WHERE e.recommendation_id = r.id AND e.evidence_text = '우대 기술 일치: ' || s.skill_name
);
