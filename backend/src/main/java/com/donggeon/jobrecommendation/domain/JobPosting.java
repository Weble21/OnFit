package com.donggeon.jobrecommendation.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "job_postings")
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "role_name", nullable = false, length = 150)
    private String roleName;

    @Column(length = 200)
    private String industry;

    @Column(name = "company_size", length = 100)
    private String companySize;

    @Column(name = "career_level", length = 100)
    private String careerLevel;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String responsibilities;

    @Column(nullable = false, length = 200)
    private String location;

    @Column
    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobPostingStatus status;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "seed_key", length = 80, unique = true)
    private String seedKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobOrigin origin = JobOrigin.LEGACY;

    @Column(name = "source_name", length = 80)
    private String sourceName;
    @Column(name = "external_id", length = 200)
    private String externalId;
    @Column(name = "collected_at")
    private Instant collectedAt;
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @ElementCollection
    @CollectionTable(name = "job_required_skills", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "skill_name", nullable = false, length = 120)
    private List<String> requiredSkills = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "job_preferred_skills", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "skill_name", nullable = false, length = 120)
    private List<String> preferredSkills = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobPosting() {
    }

    public JobPosting(String companyName, String title, String roleName, String responsibilities,
                      String location, LocalDate deadline, JobPostingStatus status) {
        this.companyName = companyName;
        this.title = title;
        this.roleName = roleName;
        this.responsibilities = responsibilities;
        this.location = location;
        this.deadline = deadline;
        this.status = status;
    }

    public static JobPosting seeded(String seedKey, String companyName, String title, String roleName,
                                    String industry, String companySize, String careerLevel, String description,
                                    String responsibilities, String location, LocalDate deadline,
                                    JobPostingStatus status, List<String> requiredSkills,
                                    List<String> preferredSkills) {
        JobPosting job = new JobPosting(companyName, title, roleName, responsibilities, location, deadline, status);
        job.seedKey = seedKey;
        job.origin = JobOrigin.SYNTHETIC;
        job.industry = industry;
        job.companySize = companySize;
        job.careerLevel = careerLevel;
        job.description = description;
        job.requiredSkills.addAll(requiredSkills);
        job.preferredSkills.addAll(preferredSkills);
        return job;
    }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getTitle() { return title; }
    public String getRoleName() { return roleName; }
    public String getIndustry() { return industry; }
    public String getCompanySize() { return companySize; }
    public String getCareerLevel() { return careerLevel; }
    public String getDescription() { return description; }
    public String getResponsibilities() { return responsibilities; }
    public String getLocation() { return location; }
    public LocalDate getDeadline() { return deadline; }
    public JobPostingStatus getStatus() { return status; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSeedKey() { return seedKey; }
    public JobOrigin getOrigin() { return origin; }
    public String getSourceName() { return sourceName; }
    public String getExternalId() { return externalId; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getContentHash() { return contentHash; }

    public static JobPosting imported(String sourceName, String externalId, Instant collectedAt) {
        JobPosting job = new JobPosting();
        job.origin = JobOrigin.REAL;
        job.sourceName = sourceName;
        job.externalId = externalId;
        job.collectedAt = collectedAt;
        return job;
    }

    public void updateImported(String sourceUrl, String companyName, String title, String roleName,
                               String industry, String companySize, String careerLevel, String description,
                               String responsibilities, String location, LocalDate deadline, JobPostingStatus status,
                               List<String> requiredSkills, List<String> preferredSkills, String hash) {
        this.sourceUrl = sourceUrl;
        this.companyName = companyName;
        this.title = title;
        this.roleName = roleName;
        this.industry = industry;
        this.companySize = companySize;
        this.careerLevel = careerLevel;
        this.description = description;
        this.responsibilities = responsibilities;
        this.location = location;
        this.deadline = deadline;
        this.status = status;
        this.requiredSkills.clear();
        this.requiredSkills.addAll(requiredSkills);
        this.preferredSkills.clear();
        this.preferredSkills.addAll(preferredSkills);
        this.contentHash = hash;
    }

    public void markSeen(Instant observedAt) { this.lastSeenAt = observedAt; }
    public List<String> getRequiredSkills() { return requiredSkills; }
    public List<String> getPreferredSkills() { return preferredSkills; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
