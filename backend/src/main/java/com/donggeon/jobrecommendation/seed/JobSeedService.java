package com.doggeon.jobrecommendation.seed;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobSeedService {

    private final JobPostingRepository repository;
    private final JsonMapper jsonMapper;

    public JobSeedService(JobPostingRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public int seed() throws IOException {
        SeedJob[] jobs;
        try (var input = new ClassPathResource("seed/job-postings.json").getInputStream()) {
            jobs = jsonMapper.readValue(input, SeedJob[].class);
        }
        if (jobs.length < 30 || jobs.length > 50) {
            throw new IllegalStateException("Expected 30-50 synthetic job postings, got " + jobs.length);
        }
        Set<String> keys = new HashSet<>();
        Set<String> existing = repository.findAllSeedKeys();
        int added = 0;
        for (SeedJob job : jobs) {
            if (job.seedKey() == null || job.seedKey().isBlank() || !keys.add(job.seedKey())
                    || job.industry() == null || job.industry().isBlank()
                    || (job.companySize() != null && job.companySize().isBlank())
                    || job.requiredSkills() == null || job.requiredSkills().isEmpty()
                    || job.preferredSkills() == null || job.responsibilities() == null
                    || job.status() == null || job.deadline() == null) {
                throw new IllegalStateException("Invalid or duplicate seed job: " + job.seedKey());
            }
            if (!existing.contains(job.seedKey())) {
                repository.save(JobPosting.seeded(job.seedKey(), job.companyName(), job.title(),
                        job.roleName(), job.industry(), job.companySize(), job.careerLevel(), job.description(),
                        job.responsibilities(), job.location(), LocalDate.parse(job.deadline()),
                        job.status(), job.requiredSkills(), job.preferredSkills()));
                added++;
            }
        }
        return added;
    }

    private record SeedJob(String seedKey, String companyName, String title, String roleName,
                           String industry, String companySize, String careerLevel, String description,
                           String responsibilities, String location, String deadline,
                           JobPostingStatus status, List<String> requiredSkills,
                           List<String> preferredSkills) {
    }
}
