package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.Recommendation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    Optional<Recommendation> findByIdAndUserEmail(Long id, String email);
    Optional<Recommendation> findFirstByUserIdAndJobPostingIdOrderByIdDesc(Long userId, Long jobId);
}
