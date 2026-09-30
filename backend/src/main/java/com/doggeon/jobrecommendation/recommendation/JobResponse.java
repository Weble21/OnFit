package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import java.time.LocalDate;
import java.util.List;

public record JobResponse(Long id, String companyName, String title, String roleName,
                          String industry, String companySize, String careerLevel, String description,
                          String responsibilities, String location, LocalDate deadline,
                          JobPostingStatus status, List<String> requiredSkills,
                          List<String> preferredSkills) {

    public static JobResponse from(JobPosting job) {
        return new JobResponse(job.getId(), job.getCompanyName(), job.getTitle(),
                job.getRoleName(), job.getIndustry(), job.getCompanySize(), job.getCareerLevel(),
                job.getDescription(), job.getResponsibilities(), job.getLocation(),
                job.getDeadline(), job.getStatus(), List.copyOf(job.getRequiredSkills()),
                List.copyOf(job.getPreferredSkills()));
    }
}
