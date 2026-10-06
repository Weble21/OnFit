package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.UserProfile;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
