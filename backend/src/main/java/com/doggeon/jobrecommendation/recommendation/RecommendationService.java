package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.Recommendation;
import com.doggeon.jobrecommendation.domain.UserProfile;
import com.doggeon.jobrecommendation.profile.UserProfileRepository;
import com.doggeon.jobrecommendation.seed.JobPostingRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationService {

    private static final String DEMO_EMAIL = "demo@onfit.local";
    private final UserProfileRepository profiles;
    private final JobPostingRepository jobs;
    private final RecommendationRepository recommendations;
    private final RecommendationCalculator calculator;

    public RecommendationService(UserProfileRepository profiles, JobPostingRepository jobs,
                                 RecommendationRepository recommendations,
                                 RecommendationCalculator calculator) {
        this.profiles = profiles;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.calculator = calculator;
    }

    @Transactional
    public List<RecommendationResponse> create() {
        UserProfile profile = profiles.findByUserEmail(DEMO_EMAIL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "먼저 데모 프로필을 만들어 주세요."));
        return jobs.findByStatusAndDeadlineGreaterThanEqualOrderByIdAsc(
                        JobPostingStatus.OPEN, LocalDate.now()).stream()
                .map(job -> calculateOrReuse(profile, job))
                .map(RecommendationResponse::from)
                .sorted(Comparator.comparing(RecommendationResponse::totalScore).reversed()
                        .thenComparing(result -> result.job().id()))
                .toList();
    }

    private Recommendation calculateOrReuse(UserProfile profile, JobPosting job) {
        RecommendationScore score = calculator.calculate(profile, job);
        return recommendations.findFirstByUserIdAndJobPostingIdOrderByIdDesc(
                        profile.getUser().getId(), job.getId())
                .filter(score::matches)
                .orElseGet(() -> recommendations.save(score.toEntity(profile.getUser(), job)));
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(Long id) {
        return recommendations.findByIdAndUserEmail(id, DEMO_EMAIL)
                .map(RecommendationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "추천 결과를 찾을 수 없습니다."));
    }
}
