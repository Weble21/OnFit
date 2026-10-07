package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.recommendation.JobResponse;
import com.donggeon.jobrecommendation.seed.JobPostingRepository;
import com.donggeon.jobrecommendation.seed.JobSeedService;

@SpringBootTest
@ActiveProfiles("test")
class JobSeedTests {

    @Autowired JobPostingRepository repository;
    @Autowired JobSeedService seedService;

    @Test
    @Transactional
    void seedsFortySyntheticJobsWithoutDuplicates() throws IOException {
        assertThat(repository.countBySeedKeyIsNotNull()).isEqualTo(40);
        assertThat(seedService.seed()).isZero();
        assertThat(repository.countBySeedKeyIsNotNull()).isEqualTo(40);
        assertThat(repository.findAll()).filteredOn(job -> job.getStatus() == JobPostingStatus.OPEN)
                .hasSize(36);
        var first = repository.findAll().stream()
                .filter(job -> "onfit-001".equals(job.getSeedKey()))
                .findFirst().orElseThrow();
        assertThat(first.getRequiredSkills()).containsExactly("Java", "Spring Boot", "PostgreSQL");
        assertThat(first.getPreferredSkills()).containsExactly("AWS", "Docker");
        assertThat(first.getIndustry()).isEqualTo("클라우드");
        assertThat(first.getCompanySize()).isNull();
        assertThat(first.getSourceUrl()).isNull();
    }

    @Test
    void keepsIndustryAndCompanySizeSeparateInResponses() {
        JobPosting job = JobPosting.seeded("size-check", "가상 회사", "개발자", "백엔드 개발자",
                "게임", "중견기업", "신입", "가상 공고", "API 개발", "서울",
                LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN, List.of("Java"), List.of());
        JobResponse response = JobResponse.from(job);
        assertThat(response.industry()).isEqualTo("게임");
        assertThat(response.companySize()).isEqualTo("중견기업");
    }
}
