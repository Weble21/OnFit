package com.donggeon.jobrecommendation.jobanalysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.donggeon.jobrecommendation.domain.JobAnalysis;

import tools.jackson.databind.JsonNode;

/**
 * Score and role analysis are separate fields: the role analysis explains the stored score and has no
 * way to change it. {@code stale} means the profile changed after this analysis ran.
 */
public record JobAnalysisResponse(
        Long id,
        JobAnalysis.InputType inputType,
        Instant createdAt,
        boolean stale,
        JsonNode posting,
        Score score,
        JobAnalysis.RoleStatus roleStatus,
        String roleError,
        JsonNode roleAnalysis,
        List<ProfileSnapshot.ItemLabel> profileItems,
        Versions versions
) {
    public record Score(BigDecimal total, BigDecimal maxTotal, BigDecimal required, BigDecimal preferred,
                        BigDecimal semantic, BigDecimal experience, BigDecimal preference,
                        JobAnalysis.SemanticStatus semanticStatus, boolean noRequirements,
                        List<String> matchedRequired, List<String> matchedPreferred, List<String> missing,
                        List<String> evidence) {}

    public record Versions(String extractionModel, String extractionPrompt, String calculator,
                           String semanticModel, String roleModel, String rolePrompt) {}

    /** Text extracted from an uploaded file, waiting for the user to review it. */
    public record Draft(Long draftId, String text, String method, int pages, boolean truncated) {}
}
