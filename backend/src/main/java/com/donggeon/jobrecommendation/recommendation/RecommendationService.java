package com.donggeon.jobrecommendation.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.Recommendation;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.profile.CurrentUser;
import com.donggeon.jobrecommendation.profile.UserProfileRepository;

@Service
public class RecommendationService {

    private final CurrentUser currentUser;
    private final UserProfileRepository profiles;
    private final JobService jobs;
    private final RecommendationRepository recommendations;
    private final RecommendationCalculator calculator;
    private final RecommendationRetention retention;
    private final SemanticScoreProvider semantic;

    public RecommendationService(UserProfileRepository profiles, JobService jobs,
                                 RecommendationRepository recommendations,
                                 RecommendationCalculator calculator, RecommendationRetention retention,
                                 SemanticScoreProvider semantic, CurrentUser currentUser) {
        this.profiles = profiles;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.calculator = calculator;
        this.retention = retention;
        this.semantic = semantic;
        this.currentUser = currentUser;
    }

    /** {@code created} is false when every result reused an identical earlier snapshot. */
    public record Result(List<RecommendationResponse> recommendations, boolean created) {
    }

    @Transactional
    public Result create() {
        UserProfile profile = profiles.findByUserEmail(currentUser.identity())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "먼저 프로필을 만들어 주세요."));
        // One query for every job's latest snapshot instead of one lookup per job.
        Map<Long, Recommendation> latest = recommendations.findLatestPerJob(profile.getUser().getId(), retention.cutoff())
                .stream().collect(Collectors.toMap(r -> r.getJobPosting().getId(), Function.identity()));
        List<Recommendation> results = new ArrayList<>();
        boolean created = false;
        List<JobPosting> openJobs = jobs.openJobs();
        SemanticScoreProvider.Result semanticResult = semantic.score(profile, openJobs);
        for (JobPosting job : openJobs) {
            RecommendationScore score = calculator.calculate(profile, job, semanticResult.score(job.getId()));
            Recommendation previous = latest.get(job.getId());
            if (previous != null && semanticResult.modelVersion().equals(previous.getModelVersion())
                    && score.matches(previous)) {
                results.add(previous);
            } else {
                results.add(recommendations.save(score.toEntity(profile.getUser(), job, semanticResult.modelVersion())));
                created = true;
            }
        }
        return new Result(results.stream().map(RecommendationResponse::from)
                .sorted(Comparator.comparing(RecommendationResponse::totalScore).reversed()
                        .thenComparing(result -> result.job().id()))
                .toList(), created);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(Long id) {
        return recommendations.findByIdAndUserEmailAndCreatedAtGreaterThanEqual(id, currentUser.identity(), retention.cutoff())
                .map(RecommendationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "추천 결과를 찾을 수 없습니다."));
    }
}
