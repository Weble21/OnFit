package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import com.doggeon.jobrecommendation.seed.JobPostingRepository;
import com.doggeon.jobrecommendation.seed.JobSeedService;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
        assertThat(first.getSourceUrl()).isNull();
    }
}
