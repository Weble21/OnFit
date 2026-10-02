package com.doggeon.jobrecommendation.evaluation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import com.doggeon.jobrecommendation.recommendation.HttpSemanticScoreProvider;
import com.doggeon.jobrecommendation.recommendation.RecommendationWeights;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Calls the real pinned model through the same HTTP client used by the recommendation API. */
public final class SemanticEvaluation {
    private SemanticEvaluation() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Report directory is required");
        String token = System.getenv("ONFIT_AI_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("ONFIT_AI_TOKEN is required");
        String url = System.getenv().getOrDefault("ONFIT_AI_URL", "http://127.0.0.1:8001/v1/semantic-score");
        boolean includeDescriptions = Boolean.parseBoolean(
                System.getenv().getOrDefault("ONFIT_AI_INCLUDE_PROJECT_DESCRIPTIONS", "false"));
        var provider = new HttpSemanticScoreProvider(true, url, token, 15000,
                includeDescriptions, JsonMapper.builder().build());
        var fixture = RecommendationEvaluation.fixture();
        var jobs = RecommendationEvaluation.jobs();
        LocalDate date = LocalDate.parse(fixture.date());
        List<JobPosting> open = new ArrayList<>();
        Map<Long, String> keyById = new HashMap<>();
        for (int i = 0; i < jobs.size(); i++) {
            var input = jobs.get(i);
            if (input.status() != JobPostingStatus.OPEN || LocalDate.parse(input.deadline()).isBefore(date)) continue;
            var job = input.entity();
            Long id = (long) (i + 1);
            ReflectionTestUtils.setField(job, "id", id);
            open.add(job);
            keyById.put(id, input.seedKey());
        }
        Map<String, Map<String, BigDecimal>> semanticScores = new HashMap<>();
        for (var input : fixture.profiles()) {
            var result = provider.score(RecommendationEvaluation.profile(input), open);
            if (!HttpSemanticScoreProvider.MODEL_VERSION.equals(result.modelVersion())
                    || result.scores().size() != open.size()) {
                throw new IllegalStateException("Pinned model evaluation failed for " + input.id());
            }
            Map<String, BigDecimal> scores = new HashMap<>();
            result.scores().forEach((id, score) -> scores.put(keyById.get(id), score));
            semanticScores.put(input.id(), scores);
        }

        var baseline = RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT);
        var withAI = RecommendationEvaluation.evaluate(fixture, jobs, RecommendationWeights.DEFAULT, semanticScores);
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Files.writeString(output.resolve("semantic-v1.json"),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(withAI) + "\n");
        Files.writeString(output.resolve("semantic-v1.md"), report(baseline, withAI, includeDescriptions));
        System.out.println("Pinned model report: " + output.toAbsolutePath());
    }

    private static String report(RecommendationEvaluation.Result baseline, RecommendationEvaluation.Result withAI,
                                 boolean includeDescriptions) {
        var report = new StringBuilder("# 실제 임베딩 순위 평가 · evaluation-v1\n\n")
                .append("모델: `").append(HttpSemanticScoreProvider.MODEL_VERSION).append("`\n\n")
                .append("입력: 가상 공고 40개, 가상 프로필 10개, 프로젝트 설명 ")
                .append(includeDescriptions ? "포함(합성 평가 전용). " : "제외(운영 기본값). ")
                .append("가중치 35/20/20/15/10 동일. 동점은 기대 쌍 미충족.\n\n")
                .append("| 프로필 | 기대 쌍 충족 0점 → 실제 모델 | Top5 기대 후보 0점 → 실제 모델 |\n")
                .append("| --- | --- | --- |\n");
        int improvements = 0;
        int regressions = 0;
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var first = baseline.profiles().get(i);
            var second = withAI.profiles().get(i);
            report.append("| ").append(first.profile()).append(" | ").append(first.agreements()).append("/")
                    .append(first.judgments().size()).append(" → ").append(second.agreements()).append("/")
                    .append(second.judgments().size()).append(" | ").append(first.top5ExpectedHits()).append(" → ")
                    .append(second.top5ExpectedHits()).append(" |\n");
            if (!first.profile().equals("backend-aliases")) {
                for (int j = 0; j < first.judgments().size(); j++) {
                    if (!first.judgments().get(j).agrees() && second.judgments().get(j).agrees()) improvements++;
                    if (first.judgments().get(j).agrees() && !second.judgments().get(j).agrees()) regressions++;
                }
            }
        }
        report.append("\n별칭 동등성 프로필을 중복 집계하지 않은 기대 쌍: 개선 ").append(improvements)
                .append("건, 악화 ").append(regressions).append("건. 순위 개선만으로 운영 채택을 결정하지 않는다.\n\n")
                .append("## 기대 쌍별 설명\n\n");
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var first = baseline.profiles().get(i);
            var second = withAI.profiles().get(i);
            report.append("### ").append(first.profile()).append("\n\n");
            for (int j = 0; j < first.judgments().size(); j++) {
                var old = first.judgments().get(j);
                var updated = second.judgments().get(j);
                String state = old.agrees() == updated.agrees() ? (updated.agrees() ? "충족 유지" : "미충족 유지")
                        : updated.agrees() ? "개선" : "악화";
                var high = second.rows().stream().filter(row -> row.job().equals(old.judgment().higher())).findFirst().orElseThrow();
                var low = second.rows().stream().filter(row -> row.job().equals(old.judgment().lower())).findFirst().orElseThrow();
                report.append("- ").append(state).append(": ").append(old.judgment().higher()).append(" > ")
                        .append(old.judgment().lower()).append(" — ").append(old.judgment().reason()).append("\n")
                        .append("  - 순위 ").append(old.higherRank()).append("/ ").append(old.lowerRank())
                        .append(" → ").append(updated.higherRank()).append("/ ").append(updated.lowerRank())
                        .append("; 의미점수 ").append(high.score().semanticScore()).append("/ ")
                        .append(low.score().semanticScore()).append("; 총점 ").append(high.score().totalScore())
                        .append("/ ").append(low.score().totalScore()).append("\n");
            }
            report.append("\n");
        }
        return report.toString();
    }
}
