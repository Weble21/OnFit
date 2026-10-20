package com.donggeon.jobrecommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One user's analysis of one confirmed posting text against a fixed profile snapshot.
 * Score columns come from the deterministic calculator; the role analysis can be retried
 * without touching them.
 */
@Entity
@Table(name = "job_analyses")
public class JobAnalysis {

    public enum InputType { TEXT, FILE, URL }
    public enum SemanticStatus { OK, DISABLED, FAILED }
    public enum RoleStatus { COMPLETED, FAILED }

    /** Everything that decides the score, frozen when the analysis is created. */
    public record Scoring(String calculatorVersion, SemanticStatus semanticStatus, String semanticModelVersion,
                          BigDecimal totalScore, BigDecimal requiredScore, BigDecimal preferredScore,
                          BigDecimal semanticScore, BigDecimal experienceScore, BigDecimal preferenceScore,
                          String detailJson) {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 8)
    private InputType inputType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_id")
    private JobAnalysisDraft draft;

    @Column(name = "source_text", nullable = false, columnDefinition = "text")
    private String sourceText;

    /** Copied from the draft, which is cleaned up long before the analysis, so the result can link to the posting. */
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "profile_snapshot", nullable = false, columnDefinition = "text")
    private String profileSnapshot;

    @Column(name = "profile_hash", nullable = false, length = 64)
    private String profileHash;

    @Column(name = "structured_posting", nullable = false, columnDefinition = "text")
    private String structuredPosting;

    @Column(name = "extraction_model", nullable = false, length = 100)
    private String extractionModel;

    @Column(name = "extraction_prompt_version", nullable = false, length = 50)
    private String extractionPromptVersion;

    @Column(name = "calculator_version", nullable = false, length = 50)
    private String calculatorVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "semantic_status", nullable = false, length = 16)
    private SemanticStatus semanticStatus;

    @Column(name = "semantic_model_version", nullable = false, length = 200)
    private String semanticModelVersion;

    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "required_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal requiredScore;

    @Column(name = "preferred_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal preferredScore;

    @Column(name = "semantic_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal semanticScore;

    @Column(name = "experience_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal experienceScore;

    @Column(name = "preference_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal preferenceScore;

    @Column(name = "score_detail", nullable = false, columnDefinition = "text")
    private String scoreDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_status", nullable = false, length = 16)
    private RoleStatus roleStatus;

    @Column(name = "role_analysis", columnDefinition = "text")
    private String roleAnalysis;

    @Column(name = "role_model", length = 100)
    private String roleModel;

    @Column(name = "role_prompt_version", length = 50)
    private String rolePromptVersion;

    @Column(name = "role_error_code", length = 50)
    private String roleErrorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobAnalysis() {
    }

    public JobAnalysis(User user, InputType inputType, JobAnalysisDraft draft, String sourceText, String sourceHash,
                       String profileSnapshot, String profileHash, String structuredPosting,
                       String extractionModel, String extractionPromptVersion, Scoring scoring) {
        this.user = user;
        this.inputType = inputType;
        this.draft = draft;
        this.sourceUrl = draft == null ? null : draft.getSourceUrl();
        this.sourceText = sourceText;
        this.sourceHash = sourceHash;
        this.profileSnapshot = profileSnapshot;
        this.profileHash = profileHash;
        this.structuredPosting = structuredPosting;
        this.extractionModel = extractionModel;
        this.extractionPromptVersion = extractionPromptVersion;
        this.calculatorVersion = scoring.calculatorVersion();
        this.semanticStatus = scoring.semanticStatus();
        this.semanticModelVersion = scoring.semanticModelVersion();
        this.totalScore = scoring.totalScore();
        this.requiredScore = scoring.requiredScore();
        this.preferredScore = scoring.preferredScore();
        this.semanticScore = scoring.semanticScore();
        this.experienceScore = scoring.experienceScore();
        this.preferenceScore = scoring.preferenceScore();
        this.scoreDetail = scoring.detailJson();
        this.roleStatus = RoleStatus.FAILED;
    }

    /** Only the explanation changes here; score columns have no setters at all. */
    public void completeRoleAnalysis(String analysisJson, String model, String promptVersion) {
        this.roleAnalysis = analysisJson;
        this.roleModel = model;
        this.rolePromptVersion = promptVersion;
        this.roleErrorCode = null;
        this.roleStatus = RoleStatus.COMPLETED;
    }

    public void failRoleAnalysis(String errorCode) {
        this.roleErrorCode = errorCode;
        this.roleStatus = RoleStatus.FAILED;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public InputType getInputType() { return inputType; }
    public JobAnalysisDraft getDraft() { return draft; }
    public String getSourceText() { return sourceText; }
    public String getSourceHash() { return sourceHash; }
    public String getProfileSnapshot() { return profileSnapshot; }
    public String getProfileHash() { return profileHash; }
    public String getStructuredPosting() { return structuredPosting; }
    public String getExtractionModel() { return extractionModel; }
    public String getExtractionPromptVersion() { return extractionPromptVersion; }
    public String getCalculatorVersion() { return calculatorVersion; }
    public SemanticStatus getSemanticStatus() { return semanticStatus; }
    public String getSemanticModelVersion() { return semanticModelVersion; }
    public BigDecimal getTotalScore() { return totalScore; }
    public BigDecimal getRequiredScore() { return requiredScore; }
    public BigDecimal getPreferredScore() { return preferredScore; }
    public BigDecimal getSemanticScore() { return semanticScore; }
    public BigDecimal getExperienceScore() { return experienceScore; }
    public BigDecimal getPreferenceScore() { return preferenceScore; }
    public String getScoreDetail() { return scoreDetail; }
    public RoleStatus getRoleStatus() { return roleStatus; }
    public String getRoleAnalysis() { return roleAnalysis; }
    public String getRoleModel() { return roleModel; }
    public String getRolePromptVersion() { return rolePromptVersion; }
    public String getSourceUrl() { return sourceUrl; }
    public String getRoleErrorCode() { return roleErrorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
