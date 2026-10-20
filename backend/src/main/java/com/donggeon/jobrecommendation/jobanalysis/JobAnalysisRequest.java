package com.donggeon.jobrecommendation.jobanalysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.donggeon.jobrecommendation.domain.JobAnalysis;

/**
 * TEXT: text typed by the user. FILE and URL: text reviewed from draft {@code draftId}, where
 * {@code confirmedTextHash} is the SHA-256 of the text the user confirmed; an edit after confirming
 * changes the text but not the hash, so the request is refused until the user confirms again.
 */
public record JobAnalysisRequest(
        @NotNull JobAnalysis.InputType inputType,
        @NotBlank @Size(max = JobAnalysisService.MAX_TEXT) String text,
        Long draftId,
        @Pattern(regexp = "^[0-9a-f]{64}$") String confirmedTextHash
) {}
