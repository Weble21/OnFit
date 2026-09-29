package com.doggeon.jobrecommendation.seed;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
    boolean existsBySeedKey(String seedKey);
    long countBySeedKeyIsNotNull();
    List<JobPosting> findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
            JobPostingStatus status, LocalDate deadline);
}
