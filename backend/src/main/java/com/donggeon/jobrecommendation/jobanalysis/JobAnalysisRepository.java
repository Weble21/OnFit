package com.donggeon.jobrecommendation.jobanalysis;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.donggeon.jobrecommendation.domain.JobAnalysis;

public interface JobAnalysisRepository extends JpaRepository<JobAnalysis, Long> {
    Optional<JobAnalysis> findByIdAndUserEmailAndCreatedAtGreaterThanEqual(Long id, String email, Instant cutoff);

    /** The same confirmed text and the same profile version give back the stored analysis. */
    Optional<JobAnalysis> findFirstByUserIdAndSourceHashAndProfileHashAndCalculatorVersionAndCreatedAtGreaterThanEqualOrderByIdDesc(
            Long userId, String sourceHash, String profileHash, String calculatorVersion, Instant cutoff);

    @Modifying
    @Query(value = "delete from job_analyses where created_at < :cutoff", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
