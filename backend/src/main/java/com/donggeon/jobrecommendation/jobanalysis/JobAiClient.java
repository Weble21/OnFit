package com.donggeon.jobrecommendation.jobanalysis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the private AI service's Claude endpoints. Only Spring decides whose data is sent: the service
 * receives text and profile items, never a user id, and it cannot write scores.
 */
@Component
public class JobAiClient {
    private static final Logger log = LoggerFactory.getLogger(JobAiClient.class);

    public record Extraction(StructuredPosting posting, String model, String promptVersion) {}
    public record RoleResult(JsonNode analysis, String model, String promptVersion) {}
    public record ProfileItem(String id, String kind, String text) {}
    public record ScoreFacts(List<String> matchedRequired, List<String> matchedPreferred, List<String> missing) {}

    record ExtractRequest(String text) {}
    record RoleRequest(String sourceText, StructuredPosting posting, List<ProfileItem> profileItems,
                       ScoreFacts scoreFacts) {}
    record Meta(String model, String promptVersion, Integer droppedItems) {}
    record ExtractResponse(StructuredPosting posting, Meta meta) {}
    record RoleResponse(JsonNode analysis, Meta meta) {}

    private final boolean enabled;
    private final URI baseUrl;
    private final String token;
    private final Duration timeout;
    private final JsonMapper json;
    private final HttpClient client;

    public JobAiClient(@Value("${onfit.llm.enabled:false}") boolean enabled,
                       @Value("${onfit.llm.base-url:http://127.0.0.1:8001}") String baseUrl,
                       @Value("${onfit.ai.token:}") String token,
                       @Value("${onfit.llm.timeout-ms:180000}") int timeoutMs,
                       JsonMapper json) {
        if (timeoutMs < 1000 || timeoutMs > 300000) throw new IllegalArgumentException("LLM timeout must be 1..300 s");
        this.enabled = enabled;
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!Set.of("http", "https").contains(this.baseUrl.getScheme())) throw new IllegalArgumentException("LLM URL must be HTTP(S)");
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Extraction extract(String sourceText) {
        var response = post("internal/jobs/extract", new ExtractRequest(sourceText), ExtractResponse.class);
        if (response.posting() == null || response.meta() == null || response.meta().model() == null) {
            throw new JobAiException("LLM_INVALID_OUTPUT");
        }
        return new Extraction(response.posting(), response.meta().model(), response.meta().promptVersion());
    }

    public RoleResult analyzeRole(String sourceText, StructuredPosting posting, List<ProfileItem> profileItems,
                                  ScoreFacts scoreFacts) {
        var response = post("internal/jobs/analyze-role",
                new RoleRequest(sourceText, posting, profileItems, scoreFacts), RoleResponse.class);
        if (response.analysis() == null || !response.analysis().isObject() || response.meta() == null
                || response.meta().model() == null) {
            throw new JobAiException("LLM_INVALID_OUTPUT");
        }
        return new RoleResult(response.analysis(), response.meta().model(), response.meta().promptVersion());
    }

    private <T> T post(String path, Object body, Class<T> type) {
        if (!enabled) throw new JobAiException("LLM_DISABLED");
        if (token.isBlank()) {
            log.warn("llm_unavailable reason=missing_token");
            throw new JobAiException("LLM_DISABLED");
        }
        try {
            var request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-OnFit-AI-Token", token)
                    .header("X-OnFit-Request-Id", MDC.get("requestId") == null ? "" : MDC.get("requestId"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new JobAiException(errorCode(response));
            return json.readValue(response.body(), type);
        } catch (JobAiException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new JobAiException("LLM_TIMEOUT");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JobAiException("LLM_UNAVAILABLE");
        } catch (java.io.IOException ex) {
            throw new JobAiException("LLM_UNAVAILABLE");
        } catch (RuntimeException ex) {
            // Response parsing failed. No body or exception message is logged: both can hold posting text.
            log.warn("llm_invalid_response exception_type={}", ex.getClass().getSimpleName());
            throw new JobAiException("LLM_INVALID_OUTPUT");
        }
    }

    private String errorCode(HttpResponse<String> response) {
        try {
            JsonNode code = json.readTree(response.body()).path("detail").path("code");
            if (code.isString() && code.asString().matches("[A-Z_]{1,40}")) return code.asString();
        } catch (RuntimeException ignored) {
            // A non-JSON error page still maps to a generic code below.
        }
        return response.statusCode() == 401 ? "LLM_AUTH" : "LLM_UPSTREAM";
    }
}
