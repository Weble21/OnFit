package com.donggeon.jobrecommendation.recommendation;

import java.math.BigDecimal;
import java.util.List;

import com.donggeon.jobrecommendation.domain.Recommendation;

public record RecommendationResponse(Long id, JobResponse job, BigDecimal totalScore,
                                     BigDecimal requiredScore, BigDecimal preferredScore,
                                     BigDecimal semanticScore, String modelVersion, BigDecimal experienceScore,
                                     BigDecimal preferenceScore, List<String> matchedRequiredSkills,
                                     List<String> matchedPreferredSkills, List<String> matchedEvidence,
                                     List<String> missingSkills) {

    public static RecommendationResponse from(Recommendation recommendation) {
        return new RecommendationResponse(recommendation.getId(),
                JobResponse.from(recommendation.getJobPosting()), recommendation.getTotalScore(),
                recommendation.getRequiredScore(), recommendation.getPreferredScore(),
                recommendation.getSemanticScore(), recommendation.getModelVersion(), recommendation.getExperienceScore(),
                recommendation.getPreferenceScore(),
                List.copyOf(recommendation.getMatchedRequiredSkills()),
                List.copyOf(recommendation.getMatchedPreferredSkills()),
                List.copyOf(recommendation.getMatchedEvidence()),
                List.copyOf(recommendation.getMissingSkills()));
    }
}
