package com.donggeon.jobrecommendation.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

// A target role is required because recommendations score against it; a preferred location is optional.
public record ProfileRequest(
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 120) String> targetRoles,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> preferredLocations,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> skills,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 200) String> certificates,
        @Size(max = 50) List<@NotNull @Valid ProjectInput> projects,
        @Size(max = 50) List<@NotNull @Valid ExperienceInput> experiences
) {
    public record ProjectInput(
            @NotBlank @Size(max = 200) String name,
            String description,
            String techStack,
            LocalDate startedOn,
            LocalDate endedOn,
            @Size(max = 1000) String projectUrl
    ) {
    }

    public record ExperienceInput(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String roleName,
            String description,
            @NotNull LocalDate startedOn,
            LocalDate endedOn
    ) {
    }
}
