package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import com.donggeon.jobrecommendation.domain.Experience;
import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.domain.Project;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.profile.SkillNormalizer;
import com.donggeon.jobrecommendation.recommendation.RecommendationCalculator;

class RecommendationCalculatorTests {

    private final RecommendationCalculator calculator = new RecommendationCalculator();

    @Test
    void weightedScoreAndEvidenceAreDeterministic() {
        UserProfile profile = profile();
        profile.getTargetRoles().add("백엔드 개발자");
        profile.getPreferredLocations().add("서울");
        profile.addSkill(new Skill("SpringBoot", "spring boot"));
        profile.addSkill(new Skill("Postgres", "postgresql"));
        profile.addSkill(new Skill("Docker", "docker"));
        profile.addExperience(new Experience("가상 회사", "백엔드 개발자", null,
                LocalDate.of(2024, 1, 1), null));
        JobPosting job = job();
        job.getRequiredSkills().addAll(java.util.List.of("Java", "Spring Boot", "PostgreSQL"));
        job.getPreferredSkills().addAll(java.util.List.of("Docker", "AWS"));

        var first = calculator.calculate(profile, job);
        var second = calculator.calculate(profile, job);

        assertThat(first).isEqualTo(second);
        assertThat(first.requiredScore()).isEqualByComparingTo("66.67");
        assertThat(first.preferredScore()).isEqualByComparingTo("50.00");
        assertThat(first.semanticScore()).isEqualByComparingTo("0.00");
        assertThat(first.experienceScore()).isEqualByComparingTo("100.00");
        assertThat(first.preferenceScore()).isEqualByComparingTo("100.00");
        assertThat(first.totalScore()).isEqualByComparingTo("58.33");
        assertThat(first.missingSkills()).containsExactly("Java");
        assertThat(first.matchedRequiredSkills()).containsExactly("Spring Boot", "PostgreSQL");
        assertThat(first.matchedPreferredSkills()).containsExactly("Docker");
        assertThat(first.matchedEvidence()).containsExactly(
                "필수 기술 일치: Spring Boot", "필수 기술 일치: PostgreSQL",
                "우대 기술 일치: Docker", "직무 경력 일치: 백엔드 개발자",
                "희망 직무 일치: 백엔드 개발자", "희망 지역 일치: 서울");
        var recommendation = first.toEntity(profile.getUser(), job);
        assertThat(recommendation.getTotalScore()).isEqualByComparingTo("58.33");
        assertThat(recommendation.getMatchedEvidence()).isEqualTo(first.matchedEvidence());
        assertThat(recommendation.getMissingSkills()).isEqualTo(first.missingSkills());
        assertThat(recommendation.getMatchedRequiredSkills()).isEqualTo(first.matchedRequiredSkills());
        assertThat(recommendation.getMatchedPreferredSkills()).isEqualTo(first.matchedPreferredSkills());
        assertThat(first.matches(recommendation)).isTrue();
    }

    @Test
    void projectStackSplitsOnFullWidthCommaButKeepsSlashNames() {
        UserProfile profile = profile();
        profile.addProject(new Project("배포 자동화", null, "Java，CI/CD; k8s", null, null, null));
        JobPosting job = job();
        job.getRequiredSkills().addAll(java.util.List.of("Java", "CI/CD", "Kubernetes"));

        var result = calculator.calculate(profile, job);

        assertThat(result.experienceScore()).isEqualByComparingTo("100.00");
        assertThat(result.matchedEvidence()).containsExactly("연관 프로젝트: 배포 자동화");
    }

    @Test
    void commonAliasesMatchPostingSkillNames() {
        UserProfile profile = profile();
        for (String name : java.util.List.of("k8s", "NodeJS", "JS")) {
            profile.addSkill(new Skill(name, SkillNormalizer.normalize(name)));
        }
        JobPosting job = job();
        job.getRequiredSkills().addAll(java.util.List.of("Kubernetes", "Node.js", "JavaScript"));

        assertThat(calculator.calculate(profile, job).matchedRequiredSkills())
                .containsExactly("Kubernetes", "Node.js", "JavaScript");
    }

    @Test
    void projectTechnologyCanProvideExperienceScore() {
        UserProfile profile = profile();
        profile.getPreferredLocations().add("서울");
        profile.addProject(new Project("예약 API", null, "Java, SpringBoot", null, null, null));
        JobPosting job = job();
        job.getRequiredSkills().addAll(java.util.List.of("Java", "Spring Boot"));

        var result = calculator.calculate(profile, job);

        assertThat(result.requiredScore()).isEqualByComparingTo("0.00");
        assertThat(result.preferredScore()).isEqualByComparingTo("0.00");
        assertThat(result.experienceScore()).isEqualByComparingTo("100.00");
        assertThat(result.preferenceScore()).isEqualByComparingTo("50.00");
        assertThat(result.totalScore()).isEqualByComparingTo("20.00");
        assertThat(result.missingSkills()).containsExactly("Java", "Spring Boot");
        assertThat(result.matchedEvidence()).containsExactly(
                "연관 프로젝트: 예약 API", "희망 지역 일치: 서울");
    }

    @Test
    void preferredLocationMatchesByTopLevelRegion() {
        UserProfile profile = profile();
        profile.getPreferredLocations().add("서울특별시 강남구");

        var result = calculator.calculate(profile, job());

        assertThat(result.preferenceScore()).isEqualByComparingTo("50.00");
        assertThat(result.matchedEvidence()).containsExactly("희망 지역 일치: 서울");
    }

    @Test
    void cityNamedLikeAnotherRegionDoesNotMatch() {
        UserProfile profile = profile();
        profile.getPreferredLocations().add("경기 광주시");
        JobPosting job = new JobPosting("가상 회사", "서버 개발자", "백엔드 개발자",
                "API 개발", "광주", LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN);

        assertThat(calculator.calculate(profile, job).preferenceScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void emptyInputsDoNotGrantFreePoints() {
        var result = calculator.calculate(profile(), job());

        assertThat(result.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.requiredScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.preferredScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.matchedEvidence()).isEmpty();
        assertThat(result.missingSkills()).isEmpty();
    }

    private UserProfile profile() {
        return new UserProfile(new User("test@example.com", "테스트 사용자"));
    }

    private JobPosting job() {
        return new JobPosting("가상 회사", "서버 개발자", "백엔드 개발자",
                "API 개발", "서울", LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN);
    }
}
