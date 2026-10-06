package com.donggeon.jobrecommendation.recommendation;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.seed.JobPostingRepository;

@Service
public class JobService {

    private final JobPostingRepository jobs;
    private final Clock clock;

    public JobService(JobPostingRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public JobPageResponse listOpen(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page는 0 이상, size는 1~100으로 입력해 주세요.");
        }
        var result = jobs.findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
                JobPostingStatus.OPEN, LocalDate.now(clock), PageRequest.of(page, size));
        return new JobPageResponse(result.getContent().stream().map(JobResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    /** Open postings whose deadline has not passed in the service time zone. */
    public List<JobPosting> openJobs() {
        return jobs.findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
                JobPostingStatus.OPEN, LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id) {
        return jobs.findById(id).map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다."));
    }
}
