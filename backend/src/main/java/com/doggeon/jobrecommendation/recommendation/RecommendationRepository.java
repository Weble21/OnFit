package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.Recommendation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    Optional<Recommendation> findByIdAndUserEmail(Long id, String email);

    @Query("""
            select r from Recommendation r
            where r.id in (select max(latest.id) from Recommendation latest
                           where latest.user.id = :userId group by latest.jobPosting.id)
            """)
    List<Recommendation> findLatestPerJob(@Param("userId") Long userId);
}
