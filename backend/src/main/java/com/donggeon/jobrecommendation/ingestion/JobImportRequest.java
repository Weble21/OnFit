package com.doggeon.jobrecommendation.ingestion;

import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record JobImportRequest(
        @NotBlank @Size(max = 80) String sourceName,
        @NotBlank @Size(max = 200) String externalId,
        @NotBlank @Size(max = 1000) String sourceUrl,
        @NotNull Instant observedAt,
        @NotNull @Valid Content content) {
    public record Content(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 120) String roleName,
            @Size(max = 100) String industry,
            @Size(max = 100) String companySize,
            @Size(max = 100) String careerLevel,
            @Size(max = 30000) String description,
            @NotBlank @Size(max = 30000) String responsibilities,
            @NotBlank @Size(max = 200) String location,
            LocalDate deadline,
            @NotNull JobPostingStatus status,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> requiredSkills,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> preferredSkills) { }
}
