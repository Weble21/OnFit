package com.doggeon.jobrecommendation.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "recommendations")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private JobPosting jobPosting;

    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "required_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal requiredScore;

    @Column(name = "preferred_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal preferredScore;

    @Column(name = "semantic_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal semanticScore;

    @Column(name = "model_version", nullable = false, length = 200)
    private String modelVersion;

    @Column(name = "experience_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal experienceScore;

    @Column(name = "preference_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal preferenceScore;

    @ElementCollection
    @CollectionTable(name = "recommendation_matched_required_skills", joinColumns = @JoinColumn(name = "recommendation_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "skill_name", nullable = false, length = 120)
    private List<String> matchedRequiredSkills = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "recommendation_matched_preferred_skills", joinColumns = @JoinColumn(name = "recommendation_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "skill_name", nullable = false, length = 120)
    private List<String> matchedPreferredSkills = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "recommendation_evidence", joinColumns = @JoinColumn(name = "recommendation_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "evidence_text", nullable = false, columnDefinition = "text")
    private List<String> matchedEvidence = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "recommendation_missing_skills", joinColumns = @JoinColumn(name = "recommendation_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "skill_name", nullable = false, length = 120)
    private List<String> missingSkills = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Recommendation() {
    }

    public Recommendation(User user, JobPosting jobPosting, BigDecimal totalScore,
                          BigDecimal requiredScore, BigDecimal preferredScore,
                          BigDecimal semanticScore, BigDecimal experienceScore,
                          BigDecimal preferenceScore) {
        this(user, jobPosting, totalScore, requiredScore, preferredScore, semanticScore,
                experienceScore, preferenceScore, "rules-only-v1");
    }

    public Recommendation(User user, JobPosting jobPosting, BigDecimal totalScore,
                          BigDecimal requiredScore, BigDecimal preferredScore,
                          BigDecimal semanticScore, BigDecimal experienceScore,
                          BigDecimal preferenceScore, String modelVersion) {
        this.user = user;
        this.jobPosting = jobPosting;
        this.totalScore = totalScore;
        this.requiredScore = requiredScore;
        this.preferredScore = preferredScore;
        this.semanticScore = semanticScore;
        this.modelVersion = modelVersion;
        this.experienceScore = experienceScore;
        this.preferenceScore = preferenceScore;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public JobPosting getJobPosting() { return jobPosting; }
    public BigDecimal getTotalScore() { return totalScore; }
    public BigDecimal getRequiredScore() { return requiredScore; }
    public BigDecimal getPreferredScore() { return preferredScore; }
    public BigDecimal getSemanticScore() { return semanticScore; }
    public String getModelVersion() { return modelVersion; }
    public BigDecimal getExperienceScore() { return experienceScore; }
    public BigDecimal getPreferenceScore() { return preferenceScore; }
    public List<String> getMatchedRequiredSkills() { return matchedRequiredSkills; }
    public List<String> getMatchedPreferredSkills() { return matchedPreferredSkills; }
    public List<String> getMatchedEvidence() { return matchedEvidence; }
    public List<String> getMissingSkills() { return missingSkills; }
    public Instant getCreatedAt() { return createdAt; }
}
