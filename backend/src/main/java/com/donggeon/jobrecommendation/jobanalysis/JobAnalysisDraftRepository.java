package com.donggeon.jobrecommendation.jobanalysis;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.donggeon.jobrecommendation.domain.JobAnalysisDraft;

public interface JobAnalysisDraftRepository extends JpaRepository<JobAnalysisDraft, Long> {
    Optional<JobAnalysisDraft> findByIdAndUserEmail(Long id, String email);

    // job_analyses.draft_id is ON DELETE SET NULL, so finished analyses outlive their drafts.
    @Modifying
    @Query(value = "delete from job_analysis_drafts where created_at < :cutoff", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
