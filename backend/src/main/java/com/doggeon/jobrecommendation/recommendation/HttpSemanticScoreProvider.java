package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.UserProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpSemanticScoreProvider implements SemanticScoreProvider {
    private static final Logger log = LoggerFactory.getLogger(HttpSemanticScoreProvider.class);
    public static final String MODEL_VERSION = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
            + "@f16484b452bc5449a3ad85665709a2648b51d735/text-v1/cal-v1";

    public record JobRequest(Long jobId, String text) {}
    public record ScoreRequest(String modelVersion, String profileText, List<JobRequest> jobs) {}
    public record JobScore(Long jobId, BigDecimal score) {}
    public record ScoreResponse(String modelVersion, List<JobScore> scores) {}

    private final boolean enabled;
    private final boolean includeProjectDescriptions;
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final JsonMapper json;
    private final HttpClient client;

    public HttpSemanticScoreProvider(@Value("${onfit.ai.enabled:false}") boolean enabled,
                                     @Value("${onfit.ai.url:http://127.0.0.1:8001/v1/semantic-score}") String url,
                                     @Value("${onfit.ai.token:}") String token,
                                     @Value("${onfit.ai.timeout-ms:5000}") int timeoutMs,
                                     @Value("${onfit.ai.include-project-descriptions:false}") boolean includeProjectDescriptions,
                                     JsonMapper json) {
        if (timeoutMs < 100 || timeoutMs > 30000) throw new IllegalArgumentException("AI timeout must be 100..30000 ms");
        this.enabled = enabled;
        this.includeProjectDescriptions = includeProjectDescriptions;
        this.endpoint = URI.create(url);
        if (!Set.of("http", "https").contains(endpoint.getScheme())) throw new IllegalArgumentException("AI URL must be HTTP(S)");
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Result score(UserProfile profile, List<JobPosting> jobs) {
        if (!enabled) return new Result(RULES_VERSION, Map.of());
        if (jobs.isEmpty()) return new Result(MODEL_VERSION, Map.of());
        if (token.isBlank()) {
            log.warn("semantic_fallback reason=missing_token");
            return fallback();
        }
        try {
            // Keep one bounded request. If a later phase exceeds 50 open jobs, chunk with an overall deadline.
            if (jobs.size() > 50) throw new IllegalStateException("Too many open jobs for one AI batch");
            var body = new ScoreRequest(MODEL_VERSION, SemanticText.profile(profile, includeProjectDescriptions),
                    jobs.stream().map(job -> new JobRequest(job.getId(), SemanticText.job(job))).toList());
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-OnFit-AI-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("AI HTTP status " + response.statusCode());
            var parsed = json.readValue(response.body(), ScoreResponse.class);
            if (!MODEL_VERSION.equals(parsed.modelVersion()) || parsed.scores() == null
                    || parsed.scores().size() != jobs.size()) throw new IllegalStateException("Invalid AI response metadata");
            Set<Long> requested = jobs.stream().map(JobPosting::getId).collect(Collectors.toSet());
            Map<Long, BigDecimal> scores = new HashMap<>();
            for (var result : parsed.scores()) {
                if (result.jobId() == null || !requested.contains(result.jobId())
                        || result.score() == null || result.score().compareTo(BigDecimal.ZERO) < 0
                        || result.score().compareTo(new BigDecimal("100")) > 0
                        || result.score().scale() > 2
                        || scores.putIfAbsent(result.jobId(), result.score().setScale(2)) != null) {
                    throw new IllegalStateException("Invalid AI score");
                }
            }
            return new Result(MODEL_VERSION, scores);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("semantic_fallback reason=interrupted");
        } catch (Exception ex) {
            // No request/response body, token, profile text or exception message in logs.
            log.warn("semantic_fallback reason={}", ex.getClass().getSimpleName());
        }
        return fallback();
    }

    private static Result fallback() { return new Result(FALLBACK_VERSION, Map.of()); }
}
