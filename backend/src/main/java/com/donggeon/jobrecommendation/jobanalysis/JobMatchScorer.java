package com.donggeon.jobrecommendation.jobanalysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.donggeon.jobrecommendation.recommendation.WebSkillMatcher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds one structured posting to the existing calculator. Weights and missing-value rules are the
 * calculator's own; this adapter only maps unknown posting fields to values that can never match.
 */
@Component
public class JobMatchScorer {
    /** Bump when the calculator, its weights or this adapter change how a score is produced. */
    public static final String CALCULATOR_VERSION = "rules-v1/preference-items/adapter-v7";
    private static final RecommendationWeights ANALYSIS_WEIGHTS = new RecommendationWeights(
            new BigDecimal("0.50"), new BigDecimal("0.20"), BigDecimal.ZERO,
            new BigDecimal("0.30"), BigDecimal.ZERO);
    private static final int MAX_SKILL_LENGTH = 120;

    public record RelatedMatch(String requirement, List<String> evidence) {}
    public record CertificateMatch(String condition, String evidence, List<String> matchedCertificates, boolean matched) {}
    public record PreferenceMatch(String condition, String evidence, List<String> profileEvidence, boolean matched) {}
    public record Detail(List<String> matchedRequired, List<String> matchedPreferred, List<String> missing,
                         List<String> evidence, boolean noRequirements, BigDecimal duty, List<String> matchedDuty,
                         List<String> missingDuty, List<RelatedMatch> relatedMatches, Integer requiredWeight, Integer dutyWeight,
                         List<CertificateMatch> requiredCertificates, List<CertificateMatch> preferredCertificates,
                         List<PreferenceMatch> preferredCriteria) {}

    private final RecommendationCalculator calculator;
    private final JsonMapper json;

    public JobMatchScorer(RecommendationCalculator calculator, JsonMapper json) {
        this.calculator = calculator;
        this.json = json;
    }

    public JobAnalysis.Scoring score(UserProfile profile, StructuredPosting posting) {
        JobPosting job = toJob(posting);
        BigDecimal semanticScore = BigDecimal.ZERO;
        RecommendationScore score = calculator.calculateForAnalysis(profile, job, ANALYSIS_WEIGHTS, semanticScore);
        boolean both = !posting.requiredSkills().isEmpty() && !posting.dutySkills().isEmpty();
        var dutyJob = toJob(new StructuredPosting(posting.companyName(), posting.title(), posting.roleName(),
                posting.careerLevel(), posting.location(), posting.responsibilities(), posting.dutySkills(),
                List.of(), posting.qualifications(), posting.ambiguities()));
        RecommendationScore dutyScore = both ? calculator.calculateForAnalysis(profile, dutyJob, ANALYSIS_WEIGHTS, semanticScore) : null;
        var allJob = toJob(posting);
        if (both) {
            Set<String> seen = allJob.getRequiredSkills().stream().map(SkillNormalizer::normalize).collect(Collectors.toSet());
            addSkills(posting.dutySkills(), allJob.getRequiredSkills(), seen);
        }
        RecommendationScore combined = both ? calculator.calculateForAnalysis(profile, allJob, ANALYSIS_WEIGHTS, semanticScore) : score;
        var requiredCertificates = posting.requiredCertificates().stream().map(c -> matchCertificate(profile, c)).toList();
        var preferredCertificates = posting.preferredCertificates().stream().map(c -> matchCertificate(profile, c)).toList();
        var preferredCriteria = posting.preferredCriteria().stream().map(c -> matchPreference(profile, c, preferredCertificates, score.matchedPreferredSkills())).toList();
        int preferredCount = preferredCriteria.isEmpty() ? job.getPreferredSkills().size() + preferredCertificates.size() : preferredCriteria.size();
        int matchedPreferredCount = preferredCriteria.isEmpty() ? score.matchedPreferredSkills().size() + (int) preferredCertificates.stream().filter(CertificateMatch::matched).count()
                : (int) preferredCriteria.stream().filter(PreferenceMatch::matched).count();
        BigDecimal preferredScore = preferredCount == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(matchedPreferredCount)
                .multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(preferredCount), 2, RoundingMode.HALF_UP);
        int requiredWeight = both ? 25 : 50;
        int dutyWeight = both ? 25 : 0;
        BigDecimal total = score.requiredScore().multiply(BigDecimal.valueOf(requiredWeight))
                .add((both ? dutyScore.requiredScore() : BigDecimal.ZERO).multiply(BigDecimal.valueOf(dutyWeight)))
                .add(preferredScore.multiply(new BigDecimal("20")))
                .add(combined.experienceScore().multiply(new BigDecimal("30")))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (requiredCertificates.stream().anyMatch(c -> !c.matched())) total = BigDecimal.ZERO.setScale(2);
        var skills = profile.getSkills().stream().map(s -> s.getNormalizedName()).collect(Collectors.toSet());
        var requirements = new java.util.ArrayList<String>();
        requirements.addAll(job.getRequiredSkills());
        requirements.addAll(job.getPreferredSkills());
        if (both) requirements.addAll(dutyJob.getRequiredSkills());
        var related = requirements.stream().distinct().map(req ->
                new RelatedMatch(req, WebSkillMatcher.related(SkillNormalizer.normalize(req), skills)))
                .filter(match -> !match.evidence().isEmpty()).toList();
        var evidence = new java.util.ArrayList<>(score.matchedEvidence().stream()
                .filter(e -> !e.startsWith("희망 직무 일치:") && !e.startsWith("희망 지역 일치:"))
                .map(e -> posting.requiredSkills().isEmpty() ? e.replace("필수 기술 일치:", "업무 기술 일치:") : e)
                .toList());
        if (both) evidence.addAll(dutyScore.matchedEvidence().stream()
                .filter(e -> e.startsWith("필수 기술 일치:") || e.startsWith("필수 기술 연관:"))
                .map(e -> e.replace("필수 기술", "업무 기술")).toList());
        boolean noRequirements = job.getRequiredSkills().isEmpty() && preferredCount == 0 && requiredCertificates.isEmpty();
        var detail = new Detail(score.matchedRequiredSkills(), score.matchedPreferredSkills(), score.missingSkills(),
                evidence, noRequirements, both ? dutyScore.requiredScore() : null,
                both ? dutyScore.matchedRequiredSkills() : List.of(), both ? dutyScore.missingSkills() : List.of(),
                related, requiredWeight, dutyWeight, requiredCertificates, preferredCertificates, preferredCriteria);
        return new JobAnalysis.Scoring(CALCULATOR_VERSION, JobAnalysis.SemanticStatus.DISABLED, SemanticScoreProvider.RULES_VERSION,
                total, score.requiredScore(), preferredScore, score.semanticScore(),
                combined.experienceScore(), BigDecimal.ZERO.setScale(2), json.writeValueAsString(detail));
    }

    /** The best total reachable with this semantic status, so a disabled model is not read as a weak fit. */
    public static BigDecimal maxTotal(String calculatorVersion, JobAnalysis.SemanticStatus status) {
        BigDecimal hundred = new BigDecimal("100.00");
        return CALCULATOR_VERSION.equals(calculatorVersion) || "rules-v1/analysis-certificates/adapter-v6".equals(calculatorVersion) || "rules-v1/analysis-web-duty/adapter-v5".equals(calculatorVersion) || status == JobAnalysis.SemanticStatus.OK
                ? hundred : new BigDecimal("80.00");
    }

    private static CertificateMatch matchCertificate(UserProfile profile, StructuredPosting.CertificateCondition condition) {
        var names = profile.getCertificates().stream().filter(c -> condition.anyItCertificate()
                ? c.getCategory() == com.donggeon.jobrecommendation.domain.CertificateCategory.TECHNICAL
                : condition.alternatives().stream().anyMatch(option -> certificateKey(option).equals(certificateKey(c.getName()))))
                .map(c -> c.getName()).toList();
        return new CertificateMatch(condition.value(), condition.evidence(), names, !names.isEmpty());
    }

    private static String certificateKey(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static PreferenceMatch matchPreference(UserProfile profile, StructuredPosting.Item item,
                                                    List<CertificateMatch> certificates, List<String> matchedSkills) {
        var found = new java.util.ArrayList<String>();
        String condition = certificateKey(item.value());
        for (var cert : certificates) {
            String certCondition = certificateKey(cert.condition());
            if (cert.matched() && (condition.contains(certCondition) || certCondition.contains(condition)
                    || (condition.contains("it관련자격증") && certCondition.contains("it관련자격증")))) {
                cert.matchedCertificates().forEach(name -> found.add("자격증 · " + name));
            }
        }
        for (String skill : matchedSkills) {
            if (condition.contains(certificateKey(skill))) found.add("기술 · " + skill);
        }
        boolean aiCondition = java.util.regex.Pattern.compile("(?i)(?<![a-z])ai(?![a-z])|인공지능|머신러닝|딥러닝")
                .matcher(item.value()).find();
        if (aiCondition && (condition.contains("프로젝트") || condition.contains("교육") || condition.contains("수업"))) {
            var ai = java.util.regex.Pattern.compile("(?i)(?<![a-z])ai(?![a-z])|인공지능|머신러닝|딥러닝");
            if (condition.contains("프로젝트")) profile.getProjects().stream()
                    .filter(p -> ai.matcher(p.getName() + " " + (p.getDescription() == null ? "" : p.getDescription())).find())
                    .forEach(p -> found.add("프로젝트 · " + p.getName()));
            if (condition.contains("교육") || condition.contains("수업")) profile.getActivities().stream()
                    .filter(a -> a.getKind() == com.donggeon.jobrecommendation.domain.ProfileActivity.Kind.TRAINING)
                    .filter(a -> ai.matcher(a.getTitle() + " " + (a.getDescription() == null ? "" : a.getDescription())).find())
                    .forEach(a -> found.add("교육 · " + a.getTitle()));
        } else if (condition.contains("교육") || condition.contains("수업") || condition.contains("프로젝트")) {
            var ignored = Set.of("관련", "경험", "경험자", "보유자", "수료자", "이수자", "프로젝트", "교육", "수업", "또는", "혹은", "우대");
            var terms = java.util.Arrays.stream(item.value().toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}+#.]+"))
                    .filter(word -> word.length() >= 2 && !ignored.contains(word)).toList();
            if (!terms.isEmpty() && condition.contains("프로젝트")) profile.getProjects().stream()
                    .filter(p -> containsTerm(p.getName() + " " + (p.getDescription() == null ? "" : p.getDescription()), terms))
                    .forEach(p -> found.add("프로젝트 · " + p.getName()));
            if (!terms.isEmpty() && (condition.contains("교육") || condition.contains("수업"))) profile.getActivities().stream()
                    .filter(a -> a.getKind() == com.donggeon.jobrecommendation.domain.ProfileActivity.Kind.TRAINING)
                    .filter(a -> containsTerm(a.getTitle() + " " + (a.getDescription() == null ? "" : a.getDescription()), terms))
                    .forEach(a -> found.add("교육 · " + a.getTitle()));
        }
        return new PreferenceMatch(item.value(), item.evidence(), List.copyOf(found), !found.isEmpty());
    }

    private static boolean containsTerm(String text, List<String> terms) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return terms.stream().anyMatch(normalized::contains);
    }

    static JobPosting toJob(StructuredPosting posting) {
        String responsibilities = posting.responsibilities().stream().map(StructuredPosting.Item::value)
                .collect(Collectors.joining("\n"));
        // Unknown role or location become "", which never equals a profile entry (those are never blank).
        JobPosting job = new JobPosting(orEmpty(posting.companyName().value()), orEmpty(posting.title().value()),
                orEmpty(posting.roleName().value()), responsibilities, orEmpty(posting.location().value()),
                null, JobPostingStatus.OPEN);
        Set<String> seen = new HashSet<>();
        addSkills(posting.requiredSkills().isEmpty() ? posting.dutySkills() : posting.requiredSkills(), job.getRequiredSkills(), seen);
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
