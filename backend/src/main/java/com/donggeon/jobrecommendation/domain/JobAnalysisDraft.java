package com.donggeon.jobrecommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/** Text extracted from an uploaded posting file or a posting address, waiting for the owner to review it. */
@Entity
@Table(name = "job_analysis_drafts")
public class JobAnalysisDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "extracted_text", nullable = false, columnDefinition = "text")
    private String extractedText;

    @Column(name = "extraction_method", nullable = false, length = 32)
    private String extractionMethod;

    @Column(nullable = false)
    private int pages;

    @Column(nullable = false)
    private boolean truncated;

    /** The posting address the text came from; null for an uploaded file. */
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JobAnalysisDraft() {
    }

    public JobAnalysisDraft(User user, String extractedText, String extractionMethod, int pages, boolean truncated) {
        this.user = user;
        this.extractedText = extractedText;
        this.extractionMethod = extractionMethod;
        this.pages = pages;
        this.truncated = truncated;
    }

    public JobAnalysisDraft(User user, String extractedText, String extractionMethod, int pages, boolean truncated,
                            String sourceUrl) {
        this(user, extractedText, extractionMethod, pages, truncated);
        this.sourceUrl = sourceUrl;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getExtractedText() { return extractedText; }
    public String getExtractionMethod() { return extractionMethod; }
    public int getPages() { return pages; }
    public boolean isTruncated() { return truncated; }
    public String getSourceUrl() { return sourceUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
