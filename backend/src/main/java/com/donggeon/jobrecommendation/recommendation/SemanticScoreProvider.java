package com.donggeon.jobrecommendation.recommendation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.UserProfile;

public interface SemanticScoreProvider {
    String RULES_VERSION = "rules-only-v1";
    String FALLBACK_VERSION = "semantic-fallback-v1";
    record Result(String modelVersion, Map<Long, BigDecimal> scores) {
        public Result {
            scores = Map.copyOf(scores);
        }
        public BigDecimal score(Long jobId) { return scores.getOrDefault(jobId, new BigDecimal("0.00")); }
    }
    Result score(UserProfile profile, List<JobPosting> jobs);
}
