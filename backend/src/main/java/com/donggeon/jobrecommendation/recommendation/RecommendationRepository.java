package com.donggeon.jobrecommendation.recommendation;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.donggeon.jobrecommendation.domain.Recommendation;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    // Child snapshot tables have ON DELETE CASCADE. Bulk deletion avoids loading every snapshot.
    @Modifying
    @Query(value = "delete from recommendations where created_at < :cutoff", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
