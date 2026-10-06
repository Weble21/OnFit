package com.donggeon.jobrecommendation.seed;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
    @Query("select j.seedKey from JobPosting j where j.seedKey is not null")
    Set<String> findAllSeedKeys();
    long countBySeedKeyIsNotNull();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<JobPosting> findBySourceNameAndExternalId(String sourceName, String externalId);
    @Query("select j from JobPosting j where j.status = :status and (j.deadline is null or j.deadline >= :deadline) order by j.id")
    List<JobPosting> findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
            JobPostingStatus status, LocalDate deadline);
    @Query("select j from JobPosting j where j.status = :status and (j.deadline is null or j.deadline >= :deadline) order by j.id")
    Page<JobPosting> findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
            JobPostingStatus status, LocalDate deadline, Pageable pageable);
}
