package com.donggeon.jobrecommendation.jobanalysis;

import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;
import com.donggeon.jobrecommendation.domain.JobAnalysis;
import tools.jackson.databind.json.JsonMapper;

/** Reuses grounded criterion decisions, never aggregate scores, across edited postings. */
@Component
public class StableCriterionEvaluation {
    private final JobAnalysisRepository analyses;
    private final JobAnalysisRetention retention;
    private final JsonMapper json;

    public StableCriterionEvaluation(JobAnalysisRepository analyses, JobAnalysisRetention retention, JsonMapper json) {
        this.analyses = analyses;
        this.retention = retention;
        this.json = json;
    }

    public JobAiClient.ScoreFacts prepare(Long userId, String profileHash, String targetRole,
            StructuredPosting posting, JobAiClient.ScoreFacts facts, JobAiClient.PromptVersions versions) {
        // Never seed the new policy with previously inconsistent whole-posting evaluations.
        if (versions == null || !"role-analysis-v13".equals(versions.role())) return facts;
        var known = new HashMap<String, JobAiClient.Rating>();
        for (var saved : analyses.findByUserIdAndProfileHashAndCalculatorVersionAndCreatedAtGreaterThanEqualOrderByIdAsc(
                userId, profileHash, JobMatchScorer.CALCULATOR_VERSION, retention.cutoff())) {
            if (saved.getRoleStatus() != JobAnalysis.RoleStatus.COMPLETED
                    || !JobAnalysisService.fromCurrentPrompts(saved, versions)
                    || saved.getTargetRole() == null) continue;
            var previous = json.readValue(saved.getStructuredPosting(), StructuredPosting.class);
            if (!context(previous).equals(context(posting))) continue;
            var detail = json.readValue(saved.getScoreDetail(), JobMatchScorer.Detail.class);
            var node = json.readTree(saved.getRoleAnalysis()).path("ratings");
            if (!node.isArray()) continue;
            var ratings = json.readValue(node.toString(), JobAiClient.Rating[].class);
            var byId = new HashMap<String, JobAiClient.Rating>();
            for (var rating : ratings) byId.put(rating.criterionId(), rating);
            for (var criterion : JobMatchScorer.evaluationCriteria(detail)) {
                var rating = byId.get(criterion.criterionId());
                boolean sameRole = JobAnalysisService.normalizeRole(saved.getTargetRole()).equals(targetRole);
                // Cross-role sharing is limited to concrete work and technology, not eligibility or domain.
                if (rating != null && (sameRole || Set.of("work", "technology").contains(criterion.category())))
                    known.putIfAbsent(key(criterion), rating);
            }
        }
        var fixed = new ArrayList<JobAiClient.Rating>();
        for (var criterion : facts.criteria()) {
            var rating = known.get(key(criterion));
            if (rating != null) fixed.add(new JobAiClient.Rating(criterion.criterionId(), rating.level(),
                    rating.status(), rating.profileEvidence(), rating.reason()));
        }
        return new JobAiClient.ScoreFacts(facts.matchedRequired(), facts.matchedPreferred(), facts.missing(),
                facts.matchedPreferences(), facts.criteria(), List.copyOf(fixed));
    }

    /** The backend rejects any attempt to revise a locked level, quotation, or rationale. */
    public void verify(JobAiClient.ScoreFacts facts, JobAiClient.RoleResult role) {
        if (facts.fixedRatings().isEmpty()) return;
        var node = role.analysis().path("ratings");
        if (!node.isArray()) throw new JobAiException("LLM_INVALID_OUTPUT");
        var returned = Arrays.asList(json.readValue(node.toString(), JobAiClient.Rating[].class));
        if (!returned.containsAll(facts.fixedRatings())) throw new JobAiException("LLM_INVALID_OUTPUT");
    }

    private static String key(JobAiClient.EvaluationCriterion criterion) {
        String evidence = normalize(criterion.postingEvidence());
        // Lists such as "개발 및 운영(Java, JSP, SQL 등)" may differ only in the other
        // technologies listed. Preserve the work wording and all experience qualifiers.
        if (criterion.category().equals("technology") && criterion.requirement() != null
                && !criterion.requirement().contains("(") && !criterion.requirement().contains("（")
                && !evidence.matches(".*(필수|우대|능숙|숙련|심화|기초|경력|이상|이하|[0-9]년).*")) {
            evidence = evidence.replaceAll("\\((?=[^\\n]*,)(?:[^()]|\\([^()]*\\))*\\)", "(기술목록)");
        }
        return String.join("\u001f", criterion.category(), criterion.evaluationType(),
                normalize(criterion.requirement()), evidence);
    }

    private String context(StructuredPosting posting) {
        // Preferred conditions are deliberately excluded; mandatory constraints and seniority remain relevant.
        return json.writeValueAsString(List.of(normalize(posting.careerLevel().value()),
                normalize(posting.careerLevel().evidence()),
                posting.qualifications().stream().map(i -> normalize(i.value()) + "|" + normalize(i.evidence())).sorted().toList(),
                posting.requiredCertificates().stream().map(i -> normalize(i.value()) + "|" + normalize(i.evidence())).sorted().toList()));
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
    }
}
