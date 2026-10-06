package com.donggeon.jobrecommendation.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.donggeon.jobrecommendation.domain.Certificate;
import com.donggeon.jobrecommendation.domain.Experience;
import com.donggeon.jobrecommendation.domain.Project;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.UserProfile;

public record ProfileResponse(
        Long id,
        Long userId,
        List<String> targetRoles,
        List<String> preferredLocations,
        List<String> skills,
        List<String> certificates,
        List<ProjectView> projects,
        List<ExperienceView> experiences,
        Instant createdAt,
        Instant updatedAt
) {
    static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                List.copyOf(profile.getTargetRoles()),
                List.copyOf(profile.getPreferredLocations()),
                @NotNull profile.getSkills().stream().map(Skill::getName).toList(),
                profile.getCertificates().stream().map(Certificate::getName).toList(),
                profile.getProjects().stream().map(ProjectView::from).toList(),
                profile.getExperiences().stream().map(ExperienceView::from).toList(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    public record ProjectView(Long id, String name, String description, String techStack,
                              LocalDate startedOn, LocalDate endedOn, String projectUrl) {
        static ProjectView from(Project project) {
            return new ProjectView(project.getId(), project.getName(), project.getDescription(),
                    project.getTechStack(), project.getStartedOn(), project.getEndedOn(),
                    project.getProjectUrl());
        }
    }

    public record ExperienceView(Long id, String companyName, String roleName, String description,
                                 LocalDate startedOn, LocalDate endedOn) {
        static ExperienceView from(Experience experience) {
            return new ExperienceView(experience.getId(), experience.getCompanyName(),
                    experience.getRoleName(), experience.getDescription(),
                    experience.getStartedOn(), experience.getEndedOn());
        }
    }
}
