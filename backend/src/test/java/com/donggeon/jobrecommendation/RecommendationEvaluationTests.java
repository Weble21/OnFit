package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donggeon.jobrecommendation.evaluation.RecommendationEvaluation;
import com.donggeon.jobrecommendation.recommendation.RecommendationWeights;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationEvaluationTests {
    @Test
    void datasetHasFortyPinnedJobsTenProfilesAndIndependentExpectations() throws Exception {
        var fixture = RecommendationEvaluation.fixture();
        var jobs = RecommendationEvaluation.jobs();
        assertThat(fixture.schemaVersion()).isEqualTo(1);
        assertThat(RecommendationEvaluation.jobsHash()).isEqualTo(fixture.jobsSha256());
        assertThat(jobs).hasSize(40);
        assertThat(jobs).extracting(RecommendationEvaluation.JobInput::seedKey).doesNotHaveDuplicates();
        assertThat(fixture.profiles()).hasSize(10).extracting(RecommendationEvaluation.ProfileInput::id).doesNotHaveDuplicates();
        var activeJobs = new HashSet<>(jobs.stream().map(RecommendationEvaluation.JobInput::seedKey).toList());
        activeJobs.removeAll(fixture.excludedJobs());
        for (var profile : fixture.profiles()) {
            assertThat(profile.description()).isNotBlank();
            assertThat(profile.expectedTopGroups()).isNotEmpty();
            assertThat(profile.expectedTopGroups().stream().flatMap(List::stream).toList())
                    .doesNotHaveDuplicates().allMatch(activeJobs::contains);
            assertThat(profile.checks()).hasSize(2);
            assertThat(profile.judgments()).isNotEmpty();
            for (var judgment : profile.judgments()) {
                assertThat(judgment.reason()).isNotBlank();
                assertThat(activeJobs).contains(judgment.higher(), judgment.lower());
                assertThat(judgment.higher()).isNotEqualTo(judgment.lower());
            }
        }
    }

    @Test
    void scoresRanksAndExplanationsMatchApprovedBaselineAndIgnoreInputOrder() throws Exception {
        var fixture = RecommendationEvaluation.fixture();
        var baseline = RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT);
        var approved = RecommendationEvaluation.readResult(Path.of("src/test/resources/evaluation/baseline-v1.json"));
        assertThat(baseline).isEqualTo(approved);
        assertThat(RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT)).isEqualTo(baseline);
        var reversedJobs = new ArrayList<>(RecommendationEvaluation.jobs());
        Collections.reverse(reversedJobs);
        assertThat(RecommendationEvaluation.evaluate(fixture, reversedJobs, RecommendationWeights.DEFAULT)).isEqualTo(baseline);
        for (var profile : baseline.profiles()) {
            assertThat(profile.rows()).hasSize(40);
            assertThat(profile.rows().stream().filter(row -> row.rank() == null).map(RecommendationEvaluation.Row::job).toList())
                    .containsExactlyElementsOf(fixture.excludedJobs());
            var ranks = profile.rows().stream().filter(row -> row.rank() != null).map(RecommendationEvaluation.Row::rank).sorted().toList();
            assertThat(ranks).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 36).boxed().toList());
            profile.rows().forEach(row -> {
                assertThat(row.score().totalScore()).isBetween(BigDecimal.ZERO, new BigDecimal("80"));
                assertThat(row.score().semanticScore()).isEqualByComparingTo("0");
            });
        }
    }

    @Test
    void explicitComponentChecksCoverNormalizationExperiencePreferencesAndMissingSkills() throws Exception {
        var fixture = RecommendationEvaluation.fixture();
        var baseline = RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT);
        for (var input : fixture.profiles()) {
            var profile = baseline.profiles().stream().filter(result -> result.profile().equals(input.id())).findFirst().orElseThrow();
            for (var expected : input.checks()) {
                var actual = profile.rows().stream().filter(row -> row.job().equals(expected.job())).findFirst().orElseThrow().score();
                assertThat(actual.requiredScore()).as("%s/%s required", input.id(), expected.job()).isEqualByComparingTo(expected.required());
                assertThat(actual.preferredScore()).isEqualByComparingTo(expected.preferred());
                assertThat(actual.experienceScore()).isEqualByComparingTo(expected.experience());
                assertThat(actual.preferenceScore()).isEqualByComparingTo(expected.preference());
                assertThat(actual.matchedRequiredSkills()).containsExactlyElementsOf(expected.matchedRequired());
                assertThat(actual.matchedPreferredSkills()).containsExactlyElementsOf(expected.matchedPreferred());
                assertThat(actual.missingSkills()).containsExactlyElementsOf(expected.missing());
            }
        }
        assertThat(baseline.profiles().get(0).rows()).isEqualTo(baseline.profiles().get(1).rows());
    }

    @Test
    void experimentExplainsBothImprovementsAndRegressionsWithoutChangingLiveWeights() throws Exception {
        var baseline = RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT);
        var candidate = RecommendationEvaluation.evaluate(RecommendationEvaluation.fixture().candidateWeights());
        var report = RecommendationEvaluation.comparison(baseline, candidate);
        assertThat(report).contains("개선:", "악화:", "미충족 유지:", "전체 순위 변화");
        assertThat(report).contains("onfit-029 > onfit-023", "onfit-008 > onfit-012");
        assertThat(RecommendationEvaluation.comparison(baseline, candidate)).isEqualTo(report);
        for (int i = 0; i < baseline.profiles().size(); i++) {
            var before = baseline.profiles().get(i);
            var after = candidate.profiles().get(i);
            for (int j = 0; j < before.rows().size(); j++) {
                var first = before.rows().get(j).score();
                var second = after.rows().get(j).score();
                assertThat(second.requiredScore()).isEqualTo(first.requiredScore());
                assertThat(second.preferredScore()).isEqualTo(first.preferredScore());
                assertThat(second.experienceScore()).isEqualTo(first.experienceScore());
                assertThat(second.preferenceScore()).isEqualTo(first.preferenceScore());
                assertThat(second.missingSkills()).isEqualTo(first.missingSkills());
            }
        }
        assertThat(RecommendationEvaluation.evaluate(RecommendationWeights.DEFAULT)).isEqualTo(baseline);
    }

    @Test
    void rejectsInvalidWeights() {
        assertThatThrownBy(() -> new RecommendationWeights(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationWeights(new BigDecimal("-0.1"), new BigDecimal("0.1"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationWeights(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
