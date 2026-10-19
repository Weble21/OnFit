-- Daily retention deletes scan by creation time, independently of the user-specific lookup index.
CREATE INDEX idx_recommendations_created_at ON recommendations(created_at);
