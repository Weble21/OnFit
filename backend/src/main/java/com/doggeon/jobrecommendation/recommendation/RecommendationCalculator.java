package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.Experience;
import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.Project;
import com.doggeon.jobrecommendation.domain.Skill;
import com.doggeon.jobrecommendation.domain.UserProfile;
import com.doggeon.jobrecommendation.profile.SkillNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RecommendationCalculator {

    private static final BigDecimal REQUIRED_WEIGHT = new BigDecimal("0.35");
    private static final BigDecimal PREFERRED_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal SEMANTIC_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal EXPERIENCE_WEIGHT = new BigDecimal("0.15");
    private static final BigDecimal PREFERENCE_WEIGHT = new BigDecimal("0.10");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    public RecommendationScore calculate(UserProfile profile, JobPosting job) {
        Set<String> skills = profile.getSkills().stream()
                .map(Skill::getNormalizedName).collect(Collectors.toSet());
        List<String> requiredSkills = job.getRequiredSkills();
        List<String> normalizedRequired = requiredSkills.stream().map(SkillNormalizer::normalize).toList();

        List<String> evidence = new ArrayList<>();
        List<String> matchedRequired = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < requiredSkills.size(); i++) {
            if (skills.contains(normalizedRequired.get(i))) {
                matchedRequired.add(requiredSkills.get(i));
                evidence.add("필수 기술 일치: " + requiredSkills.get(i));
            } else {
                missing.add(requiredSkills.get(i));
            }
        }
        List<String> matchedPreferred = new ArrayList<>();
        for (String preferred : job.getPreferredSkills()) {
            if (skills.contains(SkillNormalizer.normalize(preferred))) {
                matchedPreferred.add(preferred);
                evidence.add("우대 기술 일치: " + preferred);
            }
        }

        BigDecimal requiredScore = percentage(matchedRequired.size(), requiredSkills.size());
        BigDecimal preferredScore = percentage(matchedPreferred.size(), job.getPreferredSkills().size());
        BigDecimal semanticScore = ZERO; // FastAPI embeddings are deferred to a later phase.
        BigDecimal experienceScore = experienceScore(profile, job, normalizedRequired, evidence);
        BigDecimal preferenceScore = preferenceScore(profile, job, evidence);
        BigDecimal totalScore = requiredScore.multiply(REQUIRED_WEIGHT)
                .add(preferredScore.multiply(PREFERRED_WEIGHT))
                .add(semanticScore.multiply(SEMANTIC_WEIGHT))
                .add(experienceScore.multiply(EXPERIENCE_WEIGHT))
                .add(preferenceScore.multiply(PREFERENCE_WEIGHT))
                .setScale(2, RoundingMode.HALF_UP);
        return new RecommendationScore(totalScore, requiredScore, preferredScore, semanticScore,
                experienceScore, preferenceScore, matchedRequired, matchedPreferred, evidence, missing);
    }

    private BigDecimal experienceScore(UserProfile profile, JobPosting job, List<String> normalizedRequired,
                                       List<String> evidence) {
        String jobRole = normalizeText(job.getRoleName());
        for (Experience experience : profile.getExperiences()) {
            if (normalizeText(experience.getRoleName()).equals(jobRole)) {
                evidence.add("직무 경력 일치: " + experience.getRoleName());
                return HUNDRED;
            }
        }

        // Without an exact role match, use the best project's coverage of required skills.
        BigDecimal best = ZERO;
        String bestProject = null;
        for (Project project : profile.getProjects()) {
            Set<String> projectSkills = SkillNormalizer.split(project.getTechStack()).stream()
                    .map(SkillNormalizer::normalize).collect(Collectors.toSet());
            long matches = normalizedRequired.stream().filter(projectSkills::contains).count();
            BigDecimal score = percentage((int) matches, normalizedRequired.size());
            if (score.compareTo(best) > 0) {
                best = score;
                bestProject = project.getName();
            }
        }
        if (bestProject != null) {
            evidence.add("프로젝트 기술 연관: " + bestProject);
        }
        return best;
    }

    private BigDecimal preferenceScore(UserProfile profile, JobPosting job, List<String> evidence) {
        boolean roleMatch = profile.getTargetRoles().stream()
                .map(RecommendationCalculator::normalizeText)
                .anyMatch(role -> role.equals(normalizeText(job.getRoleName())));
        boolean locationMatch = profile.getPreferredLocations().stream()
                .map(RegionNormalizer::normalize)
                .anyMatch(location -> location.equals(RegionNormalizer.normalize(job.getLocation())));
        if (roleMatch) {
            evidence.add("희망 직무 일치: " + job.getRoleName());
        }
        if (locationMatch) {
            evidence.add("희망 지역 일치: " + job.getLocation());
        }
        return BigDecimal.valueOf((roleMatch ? 50 : 0) + (locationMatch ? 50 : 0)).setScale(2);
    }

    private static BigDecimal percentage(int matches, int total) {
        return total == 0 ? ZERO : BigDecimal.valueOf(matches).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static String normalizeText(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
