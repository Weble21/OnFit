package com.donggeon.jobrecommendation.jobanalysis;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import com.donggeon.jobrecommendation.domain.JobAnalysis;
import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.profile.SkillNormalizer;
import com.donggeon.jobrecommendation.recommendation.RecommendationCalculator;
import com.donggeon.jobrecommendation.recommendation.RecommendationScore;
import com.donggeon.jobrecommendation.recommendation.RecommendationWeights;
import com.donggeon.jobrecommendation.recommendation.SemanticScoreProvider;

import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds one structured posting to the existing calculator. Weights and missing-value rules are the
 * calculator's own; this adapter only maps unknown posting fields to values that can never match.
 */
@Component
public class JobMatchScorer {
    /** Bump when the calculator, its weights or this adapter change how a score is produced. */
    public static final String CALCULATOR_VERSION = "rules-v1/weights-default/adapter-v1";
    private static final int MAX_SKILL_LENGTH = 120;

    public record Detail(List<String> matchedRequired, List<String> matchedPreferred, List<String> missing,
                         List<String> evidence, boolean noRequirements) {}

    private final RecommendationCalculator calculator;
    private final SemanticScoreProvider semantic;
    private final JsonMapper json;

    public JobMatchScorer(RecommendationCalculator calculator, SemanticScoreProvider semantic, JsonMapper json) {
        this.calculator = calculator;
        this.semantic = semantic;
        this.json = json;
    }

    public JobAnalysis.Scoring score(UserProfile profile, StructuredPosting posting) {
        JobPosting job = toJob(posting);
        var semanticResult = semantic.scoreSingle(profile, job);
        JobAnalysis.SemanticStatus status = switch (semanticResult.modelVersion()) {
            case SemanticScoreProvider.RULES_VERSION -> JobAnalysis.SemanticStatus.DISABLED;
            case SemanticScoreProvider.FALLBACK_VERSION -> JobAnalysis.SemanticStatus.FAILED;
            default -> JobAnalysis.SemanticStatus.OK;
        };
        // A failed or disabled semantic score stays 0 and is recorded as such, not hidden as a real value.
        BigDecimal semanticScore = status == JobAnalysis.SemanticStatus.OK ? semanticResult.score() : new BigDecimal("0.00");
        RecommendationScore score = calculator.calculate(profile, job, semanticScore);
        boolean noRequirements = job.getRequiredSkills().isEmpty() && job.getPreferredSkills().isEmpty();
        var detail = new Detail(score.matchedRequiredSkills(), score.matchedPreferredSkills(), score.missingSkills(),
                score.matchedEvidence(), noRequirements);
        return new JobAnalysis.Scoring(CALCULATOR_VERSION, status, semanticResult.modelVersion(),
                score.totalScore(), score.requiredScore(), score.preferredScore(), score.semanticScore(),
                score.experienceScore(), score.preferenceScore(), json.writeValueAsString(detail));
    }

    /** The best total reachable with this semantic status, so a disabled model is not read as a weak fit. */
    public static BigDecimal maxTotal(JobAnalysis.SemanticStatus status) {
        BigDecimal hundred = new BigDecimal("100.00");
        return status == JobAnalysis.SemanticStatus.OK ? hundred
                : hundred.subtract(hundred.multiply(RecommendationWeights.DEFAULT.semantic())).setScale(2);
    }

    static JobPosting toJob(StructuredPosting posting) {
        String responsibilities = posting.responsibilities().stream().map(StructuredPosting.Item::value)
                .collect(Collectors.joining("\n"));
        // Unknown role or location become "", which never equals a profile entry (those are never blank).
        JobPosting job = new JobPosting(orEmpty(posting.companyName().value()), orEmpty(posting.title().value()),
                orEmpty(posting.roleName().value()), responsibilities, orEmpty(posting.location().value()),
                null, JobPostingStatus.OPEN);
        Set<String> seen = new HashSet<>();
        addSkills(posting.requiredSkills(), job.getRequiredSkills(), seen);
        addSkills(posting.preferredSkills(), job.getPreferredSkills(), seen);
        return job;
    }

    private static void addSkills(List<StructuredPosting.Item> items, List<String> target, Set<String> seen) {
        for (StructuredPosting.Item item : items) {
            if (item.value() == null || item.value().isBlank()) continue;
            String name = SkillNormalizer.displayName(item.value());
            if (name.length() > MAX_SKILL_LENGTH) name = name.substring(0, MAX_SKILL_LENGTH);
            if (seen.add(SkillNormalizer.normalize(name))) target.add(name);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
