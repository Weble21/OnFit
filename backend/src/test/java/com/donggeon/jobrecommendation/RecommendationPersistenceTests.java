package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.domain.Recommendation;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.recommendation.RecommendationCalculator;

@SpringBootTest
@ActiveProfiles("test")
class RecommendationPersistenceTests {

    @Autowired EntityManager entityManager;
    @Autowired RecommendationCalculator calculator;

    @Test
    @Transactional
    void calculatedScoresAndEvidencePersistTogether() {
        User user = new User("score@example.com", "점수 테스트");
        entityManager.persist(user);
        UserProfile profile = new UserProfile(user);
        profile.getTargetRoles().add("백엔드 개발자");
        profile.getPreferredLocations().add("서울");
        profile.addSkill(new Skill("SpringBoot", "spring boot"));
        entityManager.persist(profile);
        JobPosting job = new JobPosting("가상 회사", "서버 개발자", "백엔드 개발자",
                "API 개발", "서울", LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN);
        job.getRequiredSkills().addAll(java.util.List.of("Java", "Spring Boot"));
        entityManager.persist(job);

        Recommendation saved = calculator.calculate(profile, job).toEntity(user, job);
        entityManager.persist(saved);
        entityManager.flush();
        entityManager.clear();

        Recommendation reloaded = entityManager.find(Recommendation.class, saved.getId());
        assertThat(reloaded.getTotalScore()).isEqualByComparingTo("27.50");
        assertThat(reloaded.getRequiredScore()).isEqualByComparingTo("50.00");
        assertThat(reloaded.getSemanticScore()).isEqualByComparingTo("0.00");
        assertThat(reloaded.getMatchedEvidence()).containsExactly(
                "필수 기술 일치: Spring Boot", "희망 직무 일치: 백엔드 개발자", "희망 지역 일치: 서울");
        assertThat(reloaded.getMissingSkills()).containsExactly("Java");
    }
}
