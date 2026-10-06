package com.donggeon.jobrecommendation.recommendation;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationRetention {
    private final RecommendationRepository repository;
    private final Duration retention;

    public RecommendationRetention(RecommendationRepository repository,
                                   @Value("${onfit.recommendation.retention-days:30}") int days) {
        if (days < 1) throw new IllegalArgumentException("Recommendation retention must be at least one day");
        this.repository = repository;
        this.retention = Duration.ofDays(days);
    }

    // Snapshot creation uses the real UTC clock too. Demo fixed-date only controls posting deadlines.
    public Instant cutoff() {
        return Instant.now().minus(retention);
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteExpired(cutoff());
    }
}
