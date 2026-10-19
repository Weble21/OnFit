package com.donggeon.jobrecommendation.evaluation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

import com.donggeon.jobrecommendation.domain.*;
import com.donggeon.jobrecommendation.profile.SkillNormalizer;
import com.donggeon.jobrecommendation.recommendation.*;

import tools.jackson.databind.json.JsonMapper;

/** DB-free benchmark using the actual production calculator, never a second implementation of it. */
public final class RecommendationEvaluation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record ProjectInput(String name, String description, String techStack) {}
    public record ExperienceInput(String roleName, String startedOn, String endedOn) {}
    public record Judgment(String higher, String lower, String reason) {}
    public record Check(String job, BigDecimal required, BigDecimal preferred, BigDecimal experience,
                        BigDecimal preference, List<String> matchedRequired, List<String> matchedPreferred,
                        List<String> missing) {}
    public record ProfileInput(String id, String description, List<String> targetRoles, List<String> preferredLocations,
                               List<String> skills, List<ProjectInput> projects, List<ExperienceInput> experiences,
                               List<List<String>> expectedTopGroups, List<Judgment> judgments, List<Check> checks) {}
    public record Fixture(int schemaVersion, String date, String jobsSha256, List<String> excludedJobs,
                          RecommendationWeights candidateWeights, List<ProfileInput> profiles) {}
    public record JobInput(String seedKey, String companyName, String title, String roleName, String industry,
                           String companySize, String careerLevel, String description, String responsibilities,
                           String location, String deadline, JobPostingStatus status,
                           List<String> requiredSkills, List<String> preferredSkills) {
        JobPosting entity() {
            return JobPosting.seeded(seedKey, companyName, title, roleName, industry, companySize, careerLevel,
                    description, responsibilities, location, LocalDate.parse(deadline), status, requiredSkills, preferredSkills);
        }
    }
    public record Row(String job, Integer rank, RecommendationScore score) {}
    public record Outcome(Judgment judgment, int higherRank, int lowerRank, boolean agrees) {}
    public record ProfileResult(String profile, List<Row> rows, List<Outcome> judgments, int agreements,
                                int top5ExpectedHits) {}
    public record Result(int schemaVersion, String date, String jobsSha256, RecommendationWeights weights,
                         List<ProfileResult> profiles) {}

    public static Fixture fixture() throws IOException {
        return JSON.readValue(resource("/evaluation/profiles-v1.json"), Fixture.class);
    }

    public static List<JobInput> jobs() throws IOException {
        return List.of(JSON.readValue(resource("/seed/job-postings.json"), JobInput[].class));
    }

    public static String jobsHash() throws Exception {
        // Normalize line endings for Windows/Linux checkout reproducibility, retaining the JSON content.
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                new String(resource("/seed/job-postings.json"), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
    }

    public static UserProfile profile(ProfileInput input) {
        var profile = new UserProfile(new User(input.id() + "@evaluation.invalid", "가상 평가 사용자"));
        profile.getTargetRoles().addAll(input.targetRoles());
        profile.getPreferredLocations().addAll(input.preferredLocations());
        input.skills().forEach(skill -> profile.addSkill(new Skill(skill, SkillNormalizer.normalize(skill))));
        input.projects().forEach(project -> profile.addProject(new Project(project.name(), project.description(),
                project.techStack(), null, null, null)));
        input.experiences().forEach(experience -> profile.addExperience(new Experience("가상 평가 회사", experience.roleName(),
                null, LocalDate.parse(experience.startedOn()), experience.endedOn() == null ? null : LocalDate.parse(experience.endedOn()))));
        return profile;
    }

    public static Result evaluate(RecommendationWeights weights) throws Exception {
        return evaluate(fixture(), jobs(), weights);
    }

    public static Result evaluate(Fixture fixture, List<JobInput> jobs, RecommendationWeights weights) throws Exception {
        return evaluate(fixture, jobs, weights, Map.of());
    }

    public static Result evaluate(Fixture fixture, List<JobInput> jobs, RecommendationWeights weights,
                                  Map<String, Map<String, BigDecimal>> semanticScores) throws Exception {
        if (!jobsHash().equals(fixture.jobsSha256())) {
            throw new IllegalStateException("Evaluation jobs changed: review judgments and version the fixture before updating the baseline");
        }
        var calculator = new RecommendationCalculator();
        var results = new ArrayList<ProfileResult>();
        LocalDate date = LocalDate.parse(fixture.date());
        for (ProfileInput input : fixture.profiles()) {
            var profile = profile(input);
            Map<String, RecommendationScore> scores = new HashMap<>();
            Map<String, BigDecimal> semantic = semanticScores.getOrDefault(input.id(), Map.of());
            jobs.forEach(job -> scores.put(job.seedKey(), calculator.calculate(profile, job.entity(), weights,
                    semantic.getOrDefault(job.seedKey(), new BigDecimal("0.00")))));
            // Seed order is the API's ID order for a fresh seeded DB. Stable keys make reports DB-independent.
            var ranked = jobs.stream().filter(job -> job.status() == JobPostingStatus.OPEN
                    && !LocalDate.parse(job.deadline()).isBefore(date))
                    .sorted(Comparator.<JobInput, BigDecimal>comparing(job -> scores.get(job.seedKey()).totalScore())
                            .reversed().thenComparing(JobInput::seedKey)).toList();
            Map<String, Integer> ranks = new HashMap<>();
            for (int i = 0; i < ranked.size(); i++) ranks.put(ranked.get(i).seedKey(), i + 1);
            var outcomes = input.judgments().stream().map(judgment -> new Outcome(judgment,
                    ranks.get(judgment.higher()), ranks.get(judgment.lower()),
                    scores.get(judgment.higher()).totalScore().compareTo(scores.get(judgment.lower()).totalScore()) > 0)).toList();
            // Equal scores do not satisfy a strict preference, even if the deterministic tie-break places it first.
            Set<String> expected = new HashSet<>();
            input.expectedTopGroups().forEach(expected::addAll);
            results.add(new ProfileResult(input.id(), jobs.stream().sorted(Comparator.comparing(JobInput::seedKey))
                    .map(job -> new Row(job.seedKey(), ranks.get(job.seedKey()), scores.get(job.seedKey()))).toList(),
                    outcomes, (int) outcomes.stream().filter(Outcome::agrees).count(),
                    (int) ranked.stream().limit(5).filter(job -> expected.contains(job.seedKey())).count()));
        }
        return new Result(fixture.schemaVersion(), fixture.date(), fixture.jobsSha256(), weights, List.copyOf(results));
    }

    public static Result readResult(Path file) throws IOException { return JSON.readValue(Files.readAllBytes(file), Result.class); }

    private static byte[] resource(String name) throws IOException {
        try (var input = RecommendationEvaluation.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing evaluation resource " + name);
            return input.readAllBytes();
        }
    }

    /** Reports are build artifacts. Updating the approved baseline always requires an explicit copy/review. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/reports/recommendation-evaluation");
        RecommendationWeights candidate = args.length > 1
                ? JSON.readValue(Files.readAllBytes(Path.of(args[1])), RecommendationWeights.class) : fixture().candidateWeights();
        Result baseline = evaluate(RecommendationWeights.DEFAULT);
        Result changed = evaluate(candidate);
        Files.createDirectories(output);
        Files.writeString(output.resolve("baseline.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(baseline) + "\n");
        Files.writeString(output.resolve("candidate.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(changed) + "\n");
        Files.writeString(output.resolve("comparison.md"), comparison(baseline, changed));
        System.out.println("Evaluation reports: " + output.toAbsolutePath());
    }

    public static String comparison(Result baseline, Result candidate) throws IOException {
        var text = new StringBuilder("# 추천 가중치 비교 · evaluation-v1\n\n기준 날짜: ").append(baseline.date())
                .append(" · 공고 40개(순위 대상 36개) · 의미 점수 0\n\n")
                .append("운영 가중치: ").append(baseline.weights()).append("\n\n실험 가중치: ").append(candidate.weights())
                .append("\n\n엄격한 기대 순서는 점수가 더 높아야 통과한다. 동점은 미충족이다. 후보는 운영에 적용하지 않는다.\n\n")
                .append("| 프로필 | 기대 순서 충족 (기준 → 후보) | 기대 공고 Top5 포함 (기준 → 후보) | 실제 Top5 (기준 → 후보) |\n| --- | --- | --- | --- |\n");
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var before = baseline.profiles().get(i);
            var after = candidate.profiles().get(i);
            text.append("| ").append(before.profile()).append(" | ").append(before.agreements()).append("/")
                    .append(before.judgments().size()).append(" → ").append(after.agreements()).append("/")
                    .append(after.judgments().size()).append(" | ").append(before.top5ExpectedHits()).append("/5 → ")
                    .append(after.top5ExpectedHits()).append("/5 | ").append(top5(before)).append(" → ").append(top5(after)).append(" |\n");
        }
        text.append("\n## 개선·악화·미충족 사례\n\n");
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var before = baseline.profiles().get(i);
            var after = candidate.profiles().get(i);
            text.append("### ").append(before.profile()).append("\n\n");
            for (int j = 0; j < before.judgments().size(); j++) {
                var first = before.judgments().get(j);
                var second = after.judgments().get(j);
                if (first.agrees() && second.agrees()) continue;
                String state = first.agrees() ? "악화" : second.agrees() ? "개선" : "미충족 유지";
                text.append("- ").append(state).append(": ").append(first.judgment().higher()).append(" > ")
                        .append(first.judgment().lower()).append(" — ").append(first.judgment().reason()).append("\n")
                        .append("  - 기준 ").append(describe(before, first)).append("; 후보 ").append(describe(after, second)).append("\n");
            }
            text.append("\n");
        }
        text.append("## 전체 순위 변화\n\n| 프로필 | 공고 | 순위 기준 → 후보 | 점수 기준 → 후보 | 필수 / 우대 / 경험 / 희망조건 |\n| --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var before = baseline.profiles().get(i);
            var after = candidate.profiles().get(i);
            for (int j = 0; j < before.rows().size(); j++) {
                var first = before.rows().get(j);
                var second = after.rows().get(j);
                if (first.rank() == null || Objects.equals(first.rank(), second.rank())) continue;
                var score = first.score();
                text.append("| ").append(before.profile()).append(" | ").append(first.job()).append(" | ").append(first.rank())
                        .append(" → ").append(second.rank()).append(" | ").append(score.totalScore()).append(" → ")
                        .append(second.score().totalScore()).append(" | ").append(score.requiredScore()).append(" / ")
                        .append(score.preferredScore()).append(" / ").append(score.experienceScore()).append(" / ")
                        .append(score.preferenceScore()).append(" |\n");
            }
        }
        return text.toString();
    }

    private static String top5(ProfileResult profile) {
        return profile.rows().stream().filter(row -> row.rank() != null && row.rank() <= 5)
                .sorted(Comparator.comparing(Row::rank)).map(Row::job).toList().toString();
    }

    private static String describe(ProfileResult profile, Outcome outcome) {
        var higher = profile.rows().stream().filter(row -> row.job().equals(outcome.judgment().higher())).findFirst().orElseThrow();
        var lower = profile.rows().stream().filter(row -> row.job().equals(outcome.judgment().lower())).findFirst().orElseThrow();
        return higher.rank() + "위 " + higher.score().totalScore() + "점 / " + lower.rank() + "위 " + lower.score().totalScore() + "점";
    }
}
