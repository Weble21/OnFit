package com.donggeon.jobrecommendation.jobanalysis;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Analyses hold posting text and a profile copy, so they expire on the same schedule as recommendations. */
@Service
public class JobAnalysisRetention {
    private final JobAnalysisRepository analyses;
    private final JobAnalysisDraftRepository drafts;
    private final Duration retention;

    public JobAnalysisRetention(JobAnalysisRepository analyses, JobAnalysisDraftRepository drafts,
                                @Value("${onfit.recommendation.retention-days:30}") int days) {
        if (days < 1) throw new IllegalArgumentException("Analysis retention must be at least one day");
        this.analyses = analyses;
        this.drafts = drafts;
        this.retention = Duration.ofDays(days);
    }

    public Instant cutoff() {
        return Instant.now().minus(retention);
    }

    @Transactional
    public int purgeExpired() {
        Instant cutoff = cutoff();
        int removed = analyses.deleteExpired(cutoff);
        // Drafts are only usable for a day; keep them no longer than that plus a margin for retries.
        return removed + drafts.deleteExpired(Instant.now().minus(JobAnalysisService.DRAFT_TTL.multipliedBy(2)));
    }
}
