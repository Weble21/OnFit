package com.donggeon.jobrecommendation.jobanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import com.donggeon.jobrecommendation.domain.Project;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;

class RubricScorerTests {
    private static final StructuredPosting.TextField EMPTY = new StructuredPosting.TextField(null, null);

    @Test
    void replaysSavedLevelsWithCurrentFactorsAndKeepsUnknownAsRange() {
        var criteria = List.of(
                new RubricScorer.Criterion("one", "", 1, 1, "확인됨", "", 1),
                new RubricScorer.Criterion("two", "", 1, 2, "확인됨", "", 1),
                new RubricScorer.Criterion("three", "", 1, 3, "확인됨", "", 1),
                new RubricScorer.Criterion("four", "", 1, 4, "확인됨", "", 1));
        var old = new RubricScorer.Result(List.of(new RubricScorer.Category("technology", "기술", 25,
                BigDecimal.ZERO, null, null, criteria)), BigDecimal.ZERO, 25, null, "", "충족",
                List.of(), null, null, false, null);
        var recalculated = RubricScorer.recalculate(old);
        assertThat(recalculated.total()).isEqualByComparingTo("71.3");
        assertThat(recalculated.policyVersion()).isEqualTo(RubricScorer.POLICY_VERSION);

        var unknown = new RubricScorer.Criterion("four", "", 1, 0, "미확인", "", 0);
        var oldWithUnknown = new RubricScorer.Result(List.of(new RubricScorer.Category("technology", "기술", 25,
                BigDecimal.ZERO, null, null, List.of(criteria.get(0), criteria.get(1), criteria.get(2), unknown))),
                BigDecimal.ZERO, 25, null, "", "확인 필요", List.of(), null, null, false, null);
        var pending = RubricScorer.recalculate(oldWithUnknown);
        assertThat(pending.total()).isEqualByComparingTo("46.3");
        assertThat(pending.pending()).isTrue();
        assertThat(pending.lowerTotal()).isEqualByComparingTo("46.3");
        assertThat(pending.upperTotal()).isEqualByComparingTo("71.3");
    }

    private static StructuredPosting posting() {
        return new StructuredPosting(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY,
                List.of(new StructuredPosting.Item("커머스 결제 API 개발", "핵심 업무: 커머스 결제 API 개발")),
                List.of(new StructuredPosting.Item("Java", "필수: Java")), List.of(), List.of(), List.of());
    }

    @Test
    void concreteProjectEvidenceScoresWorkTechnologyAndDomainButAbsentCategoriesAreNotCounted() {
        var profile = new UserProfile(new User("concrete@example.invalid", "Test"));
        profile.addProject(new Project("결제 API", "Java로 커머스 결제 API 개발 및 장애 해결", "Java", null, null, null));
        var result = RubricScorer.score(profile, posting(), List.of(), List.of(), List.of());
        assertThat(result.appliedWeight()).isEqualTo(70);
        assertThat(result.total()).isEqualByComparingTo("100.0");
        assertThat(result.evidenceCoverage()).isEqualByComparingTo("100.0");
        assertThat(result.eligibility()).isEqualTo("충족");
        assertThat(result.categories().get(2).points()).isNull();
        assertThat(result.categories().get(4).points()).isNull();
    }

    @Test
    void aListedSkillAloneDoesNotProveAppliedExperienceOrFailEligibility() {
        var profile = new UserProfile(new User("listed@example.invalid", "Test"));
        profile.addSkill(new Skill("Java", "java"));
        var result = RubricScorer.score(profile, posting(), List.of(), List.of(), List.of());
        assertThat(result.categories().get(1).criteria().getFirst().level()).isEqualTo(1);
        assertThat(result.eligibility()).isEqualTo("확인 필요");
        assertThat(result.evidenceLevel()).isEqualTo("낮음");
        assertThat(result.total()).isEqualByComparingTo("12.5");
        assertThat(result.pending()).isTrue();
        assertThat(result.lowerTotal()).isLessThan(new java.math.BigDecimal("30"));
        assertThat(result.upperTotal()).isGreaterThan(result.lowerTotal());
    }

    @Test
    void aProjectStackListDoesNotProveEveryTechnologyWasImplemented() {
        var profile = new UserProfile(new User("stack-only@example.invalid", "Test"));
        profile.addProject(new Project("결제 API", "결제 API 구현", "Java", null, null, null));
        var result = RubricScorer.score(profile, posting(), List.of(), List.of(), List.of());
        assertThat(result.categories().get(1).criteria().getFirst().level()).isEqualTo(1);
    }

    @Test
    void groundedAiLevelsScoreSemanticExperienceAndRejectInventedEvidence() {
        var profile = new UserProfile(new User("rated@example.invalid", "Test"));
        profile.addProject(new Project("주문", "Spring REST API를 직접 구현", "Java", null, null, null));
        var base = RubricScorer.score(profile, posting(), List.of(), List.of(), List.of());
        var criteria = List.of(
                new JobAiClient.EvaluationCriterion("work:0", "work", "커머스 결제 API 개발", "", "PERFORMANCE"),
                new JobAiClient.EvaluationCriterion("technology:0", "technology", "Java", "", "PERFORMANCE"),
                new JobAiClient.EvaluationCriterion("domain:0", "domain", "커머스 결제 API 개발", "", "PERFORMANCE"),
                new JobAiClient.EvaluationCriterion("eligibility:0", "eligibility", "Java", "", "BINARY"));
        var evidence = ProfileSnapshot.of(profile).evidenceItems();
        var ref = new JobAiClient.ProfileRef(evidence.getFirst().id(), "Spring REST API를 직접 구현");
        var ratings = List.of(
                new JobAiClient.Rating("work:0", 3, "PARTIAL", List.of(ref), "API 구현의 직접 근거"),
                new JobAiClient.Rating("technology:0", 2, "PARTIAL", List.of(ref), "이전 가능한 구현 경험"),
                new JobAiClient.Rating("domain:0", null, "UNVERIFIED", List.of(), "결제 도메인 근거 부족"),
                new JobAiClient.Rating("eligibility:0", 4, "VERIFIED", List.of(ref), "프로젝트 구현과 등록 기술 스택으로 사용 경험 충족"));
        var scored = RubricScorer.evaluate(base, criteria, ratings, evidence);
        assertThat(scored.total()).isEqualByComparingTo("65.7");
        assertThat(scored.eligibility()).isEqualTo("충족");
        assertThat(scored.eligibilityNotes()).isEmpty();
        assertThat(scored.categories().get(3).criteria().getFirst().level()).isNull();
        var forged = new java.util.ArrayList<>(ratings);
        forged.set(0, new JobAiClient.Rating("work:0", 4, "VERIFIED",
                List.of(new JobAiClient.ProfileRef(ref.itemId(), "상용 결제 시스템 운영")), "허위 근거"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RubricScorer.evaluate(base, criteria, forged, evidence))
                .isInstanceOf(JobAiException.class);
    }

    @Test
    void userPreferenceAloneCannotProduceAJobFitScore() {
        var profile = new UserProfile(new User("preference-only@example.invalid", "Test"));
        profile.getTargetRoles().add("백엔드 개발자");
        var noCriteria = new StructuredPosting(EMPTY, EMPTY,
                new StructuredPosting.TextField("백엔드 개발자", "백엔드 개발자"), EMPTY, EMPTY,
                List.of(), List.of(), List.of(), List.of(), List.of());
        var result = RubricScorer.score(profile, noCriteria, List.of(), List.of(), List.of());
        assertThat(result.total()).isNull();
        assertThat(result.categories().get(5).points()).isEqualByComparingTo("5.0");
    }

    @Test
    void qualificationsUseProfileEvidenceAndExplicitMismatchIsKeptSeparate() {
        var profile = new UserProfile(new User("educated@example.invalid", "Test"));
        profile.setUniversityEducation("한국대학교", "컴퓨터공학", "GRADUATED");
        var posting = new StructuredPosting(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, List.of(), List.of(), List.of(),
                List.of(new StructuredPosting.Item("관련 전공 졸업", "컴퓨터 관련 전공 졸업자")), List.of());
        var base = RubricScorer.score(profile, posting, List.of(), List.of(), List.of());
        var criteria = List.of(new JobAiClient.EvaluationCriterion("eligibility:0", "eligibility",
                "관련 전공 졸업", "컴퓨터 관련 전공 졸업자", "BINARY"));
        var evidence = ProfileSnapshot.of(profile).evidenceItems();
        var ref = new JobAiClient.ProfileRef("education:university", "전공: 컴퓨터공학 / 학적: 졸업");
        var matched = RubricScorer.evaluate(base, criteria, List.of(new JobAiClient.Rating("eligibility:0", 4,
                "VERIFIED", List.of(ref), "컴퓨터공학 전공 졸업 확인")), evidence);
        assertThat(matched.eligibility()).isEqualTo("충족");
        assertThat(matched.eligibilityNotes()).isEmpty();
        assertThat(matched.conditions().getFirst().profileEvidence()).contains("컴퓨터공학");
        assertThat(matched.total()).isNull(); // Eligibility never invents a weighted job-fit criterion.
        var unknown = RubricScorer.evaluate(base, criteria, List.of(new JobAiClient.Rating("eligibility:0", null,
                "UNVERIFIED", List.of(), "졸업 정보 부족")), List.of());
        assertThat(unknown.eligibility()).isEqualTo("확인 필요");
        var enrolled = List.of(new JobAiClient.ProfileItem("education:university", "education", "학적: 재학"));
        var unmet = RubricScorer.evaluate(base, criteria, List.of(new JobAiClient.Rating("eligibility:0", 0,
                "VERIFIED_MISMATCH", List.of(new JobAiClient.ProfileRef("education:university", "학적: 재학")),
                "졸업 필수이나 현재 재학")), enrolled);
        assertThat(unmet.eligibility()).isEqualTo("미충족");
        assertThat(unmet.eligibilityNotes().getFirst()).contains("미충족");
    }

    @Test
    void registeredCertificateSurvivesAiOmission() {
        var profile = new UserProfile(new User("certified@example.invalid", "Test"));
        profile.addCertificate(new com.donggeon.jobrecommendation.domain.Certificate("정보처리기사",
                com.donggeon.jobrecommendation.domain.CertificateCategory.TECHNICAL, null, "합격"));
        var empty = new StructuredPosting(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, List.of(), List.of(), List.of(), List.of(), List.of());
        var base = RubricScorer.score(profile, empty, List.of(new JobMatchScorer.CertificateMatch(
                "정보처리기사 필수", "정보처리기사 필수", List.of("정보처리기사"), true)), List.of(), List.of());
        var criteria = List.of(new JobAiClient.EvaluationCriterion("eligibility:0", "eligibility",
                "정보처리기사 필수", "정보처리기사 필수", "BINARY"));
        var rated = RubricScorer.evaluate(base, criteria, List.of(new JobAiClient.Rating("eligibility:0", null,
                "UNVERIFIED", List.of(), "자격증 확인 필요")), ProfileSnapshot.of(profile).evidenceItems());
        assertThat(rated.eligibility()).isEqualTo("충족");
        assertThat(rated.conditions().getFirst().profileEvidence()).contains("정보처리기사");
        var numeric = RubricScorer.score(profile, empty, List.of(new JobMatchScorer.CertificateMatch(
                "TOEIC 900점 이상", "TOEIC 900점 이상", List.of("TOEIC"), true)), List.of(), List.of());
        assertThat(numeric.conditions().getFirst().level()).isNull();
        assertThat(numeric.eligibility()).isEqualTo("확인 필요");
    }
}
