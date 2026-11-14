package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.donggeon.jobrecommendation.domain.*;
import com.donggeon.jobrecommendation.jobanalysis.*;
import com.donggeon.jobrecommendation.profile.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:onfit_detached;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DetachedJobAnalysisTests {
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JobMatchScorer scorer;

    @Test
    void preferredCriteriaCountPerPostingItemAndEducationOrProjectCanSatisfyOneItem() {
        var profile = new UserProfile(new User("preference@example.invalid", "Test"));
        profile.addCertificate(new Certificate("정보처리기사", CertificateCategory.TECHNICAL, null, null));
        var empty = new StructuredPosting.TextField(null, null);
        var cert = new StructuredPosting.CertificateCondition("IT 관련 자격증 보유자", "IT 관련 자격증 보유자", List.of(), true);
        var ai = new StructuredPosting.Item("AI 관련 프로젝트 경험자 혹은 AI 관련 교육 수료자", "AI 관련 프로젝트 경험자 혹은 AI 관련 교육 수료자");
        var certItem = new StructuredPosting.Item("IT 관련 자격증 보유자", "IT 관련 자격증 보유자");
        var third = new StructuredPosting.Item("금융 지식 보유자", "금융 지식 보유자");
        var posting = new StructuredPosting(empty, empty, empty, empty, empty, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(cert), List.of(ai, certItem));
        assertThat(scorer.score(profile, posting).preferredScore()).isEqualByComparingTo("50");
        var threeItems = new StructuredPosting(empty, empty, empty, empty, empty, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(cert), List.of(ai, certItem, third));
        assertThat(scorer.score(profile, threeItems).preferredScore()).isEqualByComparingTo("33.33");
        profile.addActivity(new ProfileActivity(ProfileActivity.Kind.TRAINING, "인공지능 수업", "대학교", "모델 학습", null, null));
        var withEducation = scorer.score(profile, posting);
        assertThat(withEducation.preferredScore()).isEqualByComparingTo("100");
        assertThat(withEducation.detailJson()).contains("교육 · 인공지능 수업");
        var projectProfile = new UserProfile(new User("ai-project@example.invalid", "Test"));
        projectProfile.addProject(new Project("분류기", "AI 모델 학습 및 평가", null, null, null, null));
        assertThat(scorer.score(projectProfile, posting).preferredScore()).isEqualByComparingTo("50");
    }

    @Test
    void missingRequiredCertificateNeedsConfirmationWithoutErasingFitScore() {
        var profile = new UserProfile(new User("cert@example.invalid", "Test"));
        profile.addSkill(new Skill("Java", "java"));
        profile.addProject(new Project("API", "API 개발", "Java", null, null, null));
        profile.addCertificate(new Certificate("정보처리기사", CertificateCategory.TECHNICAL, null, null));
        var empty = new StructuredPosting.TextField(null, null);
        var required = new StructuredPosting.CertificateCondition("정보보안기사 또는 정보처리기사 필수", "정보보안기사 또는 정보처리기사 필수", List.of("정보보안기사", "정보처리기사"), false);
        var preferred = new StructuredPosting.CertificateCondition("IT 관련 자격증 우대", "IT 관련 자격증 우대", List.of(), true);
        var posting = new StructuredPosting(empty, empty, empty, empty, empty, List.of(),
                List.of(new StructuredPosting.Item("Java", "Java")), List.of(), List.of(), List.of(), List.of(), List.of(required), List.of(preferred));
        var score = scorer.score(profile, posting);
        assertThat(score.preferredScore()).isEqualByComparingTo("100");
        assertThat(score.totalScore()).isPositive();
        assertThat(score.detailJson()).contains("정보처리기사", "requiredCertificates", "preferredCertificates");

        var otherRequired = new StructuredPosting.CertificateCondition("정보보안기사 필수", "정보보안기사 필수", List.of("정보보안기사"), false);
        var missing = new StructuredPosting(empty, empty, empty, empty, empty, List.of(),
                List.of(new StructuredPosting.Item("Java", "Java")), List.of(), List.of(), List.of(), List.of(), List.of(otherRequired), List.of(preferred));
        var missingScore = scorer.score(profile, missing);
        assertThat(missingScore.totalScore()).isEqualByComparingTo(score.totalScore());
        assertThat(missingScore.detailJson()).contains("확인 필요", "지원조건 확인 필요: 정보보안기사 필수");

        var languageOnly = new UserProfile(new User("language@example.invalid", "Test"));
        languageOnly.addCertificate(new Certificate("TOEIC", CertificateCategory.LANGUAGE, null, "900"));
        assertThat(scorer.score(languageOnly, posting).preferredScore()).isEqualByComparingTo("0");
    }

    @Test
    void broadWebRequirementAndSeparateDutyToolsUseGroundedEvidence() {
        var profile = new UserProfile(new User("web@example.invalid", "Test"));
        for (String name : List.of("Java", "Spring Boot", "JavaScript", "CSS", "Node.js")) {
            profile.addSkill(new Skill(name, SkillNormalizer.normalize(name)));
        }
        profile.addProject(new Project("Server API", null, "Java, Spring Boot", null, null, null));
        profile.addProject(new Project("Web UI", null, "JavaScript, CSS, Node.js", null, null, null));
        var empty = new StructuredPosting.TextField(null, null);
        var posting = new StructuredPosting(empty, empty, empty, empty, empty, List.of(),
                List.of(new StructuredPosting.Item("Web기술", "Web기술 활용 가능자")), List.of(), List.of(), List.of(),
                List.of(new StructuredPosting.Item("JAVA", "JAVA"), new StructuredPosting.Item("JSP", "JSP"),
                        new StructuredPosting.Item("SQL", "SQL"), new StructuredPosting.Item("Spring", "Spring")));
        var score = scorer.score(profile, posting);
        assertThat(score.requiredScore()).isEqualByComparingTo("100");
        assertThat(score.experienceScore()).isEqualByComparingTo("40");
        assertThat(score.totalScore()).isPositive().isLessThan(new java.math.BigDecimal("50"));
        assertThat(score.detailJson()).contains("Web기술", "spring boot", "javascript", "css", "node.js",
                "matchedDuty", "JAVA", "Spring", "missingDuty", "JSP", "SQL");
    }

    @Test
    void oneUnrelatedTechnologyDoesNotSatisfyWebRequirement() {
        var profile = new UserProfile(new User("nonweb@example.invalid", "Test"));
        profile.addSkill(new Skill("Java", "java"));
        profile.addSkill(new Skill("PostgreSQL", "postgresql"));
        var empty = new StructuredPosting.TextField(null, null);
        var posting = new StructuredPosting(empty, empty, empty, empty, empty, List.of(),
                List.of(new StructuredPosting.Item("Web기술", "Web기술")), List.of(), List.of(), List.of());
        var score = scorer.score(profile, posting);
        assertThat(score.requiredScore()).isEqualByComparingTo("0");
        assertThat(score.detailJson()).contains("Web기술", "missing");
        profile.addSkill(new Skill("CSS", "css"));
        profile.addSkill(new Skill("CSS3", "css3"));
        assertThat(scorer.score(profile, posting).requiredScore()).isEqualByComparingTo("0");
    }

    @Test
    void explicitDutyToolsAreComparedWithoutCallingThemRequiredQualifications() {
        var profile = new UserProfile(new User("duty@example.invalid", "Test"));
        profile.addSkill(new Skill("Java", "java"));
        profile.addProject(new Project("API", "API 개발", "Java", null, null, null));
        var empty = new StructuredPosting.TextField(null, null);
        var posting = new StructuredPosting(empty, empty, empty, empty, empty, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(new StructuredPosting.Item("JAVA", "JAVA 개발")));
        var score = scorer.score(profile, posting);
        assertThat(score.totalScore()).isPositive();
        assertThat(score.requiredScore()).isEqualByComparingTo("100");
        assertThat(score.experienceScore()).isEqualByComparingTo("100");
        assertThat(score.detailJson()).contains("업무 기술 일치: JAVA").doesNotContain("필수 기술 일치:");
    }

    @Test
    void scoresAfterReadTransactionClosesWithExplicitPreferencesSnapshotted() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var user = users.save(new User("detached@example.invalid", "Test"));
            var profile = new UserProfile(user);
            profile.getTargetRoles().add("백엔드 개발자");
            profile.getPreferredLocations().add("서울");
            profile.addSkill(new Skill("Java", "java"));
            profile.addProject(new Project("API", "API 개발", "Java", null, null, null));
            profiles.save(profile);
        });
        var detached = tx.execute(status -> {
            var profile = profiles.findByUserEmail("detached@example.invalid").orElseThrow();
            ProfileSnapshot.of(profile);
            assertThat(Hibernate.isInitialized(profile.getTargetRoles())).isTrue();
            assertThat(Hibernate.isInitialized(profile.getPreferredLocations())).isTrue();
            return profile;
        });
        var empty = new StructuredPosting.TextField(null, null);
        var posting = new StructuredPosting(empty, empty, new StructuredPosting.TextField("백엔드 개발자", "백엔드 개발자"),
                empty, empty, List.of(), List.of(new StructuredPosting.Item("Java", "Java")), List.of(), List.of(), List.of());
        var score = scorer.score(detached, posting);
        assertThat(score.totalScore()).isPositive();
        assertThat(score.preferenceScore()).isEqualByComparingTo("0");
        assertThat(score.detailJson()).contains("사용자 선호", "희망 직무");
    }
}
