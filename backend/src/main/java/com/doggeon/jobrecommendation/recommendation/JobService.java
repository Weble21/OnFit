package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import com.doggeon.jobrecommendation.seed.JobPostingRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {

    private final JobPostingRepository jobs;

    public JobService(JobPostingRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public List<JobResponse> listOpen() {
        return jobs.findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
                JobPostingStatus.OPEN, LocalDate.now()).stream().map(JobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id) {
        return jobs.findById(id).map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다."));
    }
}
