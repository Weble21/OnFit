package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.Recommendation;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    Optional<Recommendation> findByIdAndUserEmailAndCreatedAtGreaterThanEqual(Long id, String email, Instant cutoff);

    @Query("""
            select r from Recommendation r
            where r.id in (select max(latest.id) from Recommendation latest
                           where latest.user.id = :userId and latest.createdAt >= :cutoff
                           group by latest.jobPosting.id)
            """)
    List<Recommendation> findLatestPerJob(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);

    // Child snapshot tables have ON DELETE CASCADE. Bulk deletion avoids loading every snapshot.
    @Modifying
    @Query(value = "delete from recommendations where created_at < :cutoff", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
