package com.doggeon.jobrecommendation.seed;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
    @Query("select j.seedKey from JobPosting j where j.seedKey is not null")
    Set<String> findAllSeedKeys();
    long countBySeedKeyIsNotNull();
    List<JobPosting> findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
            JobPostingStatus status, LocalDate deadline);
}
