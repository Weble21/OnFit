package com.donggeon.jobrecommendation.recommendation;

import java.math.BigDecimal;
import java.util.List;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.Recommendation;
import com.donggeon.jobrecommendation.domain.User;

public record RecommendationScore(BigDecimal totalScore, BigDecimal requiredScore,
                                  BigDecimal preferredScore, BigDecimal semanticScore,
                                  BigDecimal experienceScore, BigDecimal preferenceScore,
                                  List<String> matchedRequiredSkills, List<String> matchedPreferredSkills,
                                  List<String> matchedEvidence, List<String> missingSkills) {

    public RecommendationScore {
        matchedRequiredSkills = List.copyOf(matchedRequiredSkills);
        matchedPreferredSkills = List.copyOf(matchedPreferredSkills);
        matchedEvidence = List.copyOf(matchedEvidence);
        missingSkills = List.copyOf(missingSkills);
    }

    public Recommendation toEntity(User user, JobPosting job) {
        return toEntity(user, job, SemanticScoreProvider.RULES_VERSION);
    }

    public Recommendation toEntity(User user, JobPosting job, String modelVersion) {
        Recommendation recommendation = new Recommendation(user, job, totalScore, requiredScore,
                preferredScore, semanticScore, experienceScore, preferenceScore, modelVersion);
        recommendation.getMatchedRequiredSkills().addAll(matchedRequiredSkills);
        recommendation.getMatchedPreferredSkills().addAll(matchedPreferredSkills);
        recommendation.getMatchedEvidence().addAll(matchedEvidence);
        recommendation.getMissingSkills().addAll(missingSkills);
        return recommendation;
    }

    public boolean matches(Recommendation existing) {
        return totalScore.equals(existing.getTotalScore())
                && requiredScore.equals(existing.getRequiredScore())
                && preferredScore.equals(existing.getPreferredScore())
                && semanticScore.equals(existing.getSemanticScore())
                && experienceScore.equals(existing.getExperienceScore())
                && preferenceScore.equals(existing.getPreferenceScore())
                && matchedRequiredSkills.equals(existing.getMatchedRequiredSkills())
                && matchedPreferredSkills.equals(existing.getMatchedPreferredSkills())
                && matchedEvidence.equals(existing.getMatchedEvidence())
                && missingSkills.equals(existing.getMissingSkills());
    }
}
