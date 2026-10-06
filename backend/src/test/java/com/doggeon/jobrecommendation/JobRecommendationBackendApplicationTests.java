package com.doggeon.jobrecommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.domain.Recommendation;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;

@SpringBootTest
@ActiveProfiles("test")
class JobRecommendationBackendApplicationTests {

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional
	void persistsProfileJobAndRecommendation() {
		User user = new User("test@example.com", "테스트 사용자");
		entityManager.persist(user);

		UserProfile profile = new UserProfile(user);
		profile.getTargetRoles().add("백엔드 개발자");
		profile.getPreferredLocations().add("서울");
		profile.addSkill(new Skill("Spring Boot", "spring boot"));
		entityManager.persist(profile);

		JobPosting job = new JobPosting("테스트 기업", "백엔드 개발자", "백엔드 개발자",
				"API 개발", "서울", LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN);
		job.getRequiredSkills().add("Spring Boot");
		job.getPreferredSkills().add("Docker");
		entityManager.persist(job);

		Recommendation recommendation = new Recommendation(user, job, new BigDecimal("52.50"),
				new BigDecimal("100.00"), new BigDecimal("50.00"), BigDecimal.ZERO,
				new BigDecimal("50.00"), BigDecimal.ZERO);
		recommendation.getMatchedEvidence().add("Spring Boot 프로젝트");
		recommendation.getMissingSkills().add("Docker");
		entityManager.persist(recommendation);
		entityManager.flush();
		entityManager.clear();

		UserProfile savedProfile = entityManager.find(UserProfile.class, profile.getId());
		JobPosting savedJob = entityManager.find(JobPosting.class, job.getId());
		Recommendation savedRecommendation = entityManager.find(Recommendation.class, recommendation.getId());

		assertEquals("spring boot", savedProfile.getSkills().getFirst().getNormalizedName());
		assertEquals("서울", savedProfile.getPreferredLocations().getFirst());
		assertEquals("Spring Boot", savedJob.getRequiredSkills().getFirst());
		assertEquals(new BigDecimal("52.50"), savedRecommendation.getTotalScore());
		assertEquals("Spring Boot 프로젝트", savedRecommendation.getMatchedEvidence().getFirst());
		assertEquals("Docker", savedRecommendation.getMissingSkills().getFirst());
	}
}
