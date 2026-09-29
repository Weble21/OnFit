package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.Recommendation;
import com.doggeon.jobrecommendation.domain.User;
import java.math.BigDecimal;
import java.util.List;

public record RecommendationScore(BigDecimal totalScore, BigDecimal requiredScore,
                                  BigDecimal preferredScore, BigDecimal semanticScore,
                                  BigDecimal experienceScore, BigDecimal preferenceScore,
                                  List<String> matchedEvidence, List<String> missingSkills) {

    public RecommendationScore {
        matchedEvidence = List.copyOf(matchedEvidence);
        missingSkills = List.copyOf(missingSkills);
    }

    public Recommendation toEntity(User user, JobPosting job) {
        Recommendation recommendation = new Recommendation(user, job, totalScore, requiredScore,
                preferredScore, semanticScore, experienceScore, preferenceScore);
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
                && matchedEvidence.equals(existing.getMatchedEvidence())
                && missingSkills.equals(existing.getMissingSkills());
    }
}
