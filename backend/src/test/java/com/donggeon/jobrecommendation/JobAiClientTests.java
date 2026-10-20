package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donggeon.jobrecommendation.jobanalysis.JobAiClient;
import com.donggeon.jobrecommendation.jobanalysis.JobAiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class JobAiClientTests {
    private static final String POSTING = """
            {"companyName":{"value":null,"evidence":null},"title":{"value":null,"evidence":null},
             "roleName":{"value":"백엔드 개발자","evidence":"백엔드 개발자"},"careerLevel":{"value":null,"evidence":null},
             "location":{"value":null,"evidence":null},"responsibilities":[],
             "requiredSkills":[{"value":"Java","evidence":"Java 경험"}],"preferredSkills":[],"qualifications":[],"ambiguities":[]}
            """;

    @Test
    void sendsTokenAndRequestIdAndMapsServiceErrorsToCodes() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> answer = new AtomicReference<>(
                "{\"posting\":" + POSTING + ",\"meta\":{\"model\":\"claude-opus-5-5\",\"promptVersion\":\"job-extract-v1\",\"droppedItems\":0}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/jobs/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-OnFit-AI-Token"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-OnFit-Request-Id"));
            byte[] response = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new JobAiClient(true, url, "secret", 5000, JsonMapper.builder().build());
            MDC.put("requestId", "0b7c6a2e-1f4d-4c52-9b7e-2d9f0b7a1c33");
            var result = client.extract("백엔드 개발자 공고 원문입니다. Java 경험");
            assertThat(result.posting().requiredSkills().get(0).value()).isEqualTo("Java");
            assertThat(result.posting().companyName().value()).isNull();
            assertThat(result.model()).isEqualTo("claude-opus-5-5");
            assertThat(token.get()).isEqualTo("secret");
            assertThat(requestId.get()).isEqualTo("0b7c6a2e-1f4d-4c52-9b7e-2d9f0b7a1c33");
            // Only the text crosses the boundary; the service never learns who asked.
            assertThat(body.get()).isEqualTo("{\"text\":\"백엔드 개발자 공고 원문입니다. Java 경험\"}");

            status.set(504);
            answer.set("{\"detail\":{\"code\":\"LLM_TIMEOUT\"}}");
            assertThatThrownBy(() -> client.extract("text")).isInstanceOf(JobAiException.class).hasMessage("LLM_TIMEOUT");
            status.set(500);
            answer.set("<html>Internal Server Error</html>");
            assertThatThrownBy(() -> client.extract("text")).hasMessage("LLM_UPSTREAM");
            status.set(200);
            answer.set("{\"analysis\":[],\"meta\":{\"model\":\"m\"}}");
            assertThatThrownBy(() -> client.analyzeRole("text", result.posting(), List.of(),
                    new JobAiClient.ScoreFacts(List.of(), List.of(), List.of()))).hasMessage("LLM_INVALID_OUTPUT");

            var disabled = new JobAiClient(false, url, "secret", 5000, JsonMapper.builder().build());
            assertThatThrownBy(() -> disabled.extract("text")).hasMessage("LLM_DISABLED");
            var noToken = new JobAiClient(true, url, "", 5000, JsonMapper.builder().build());
            assertThatThrownBy(() -> noToken.extract("text")).hasMessage("LLM_DISABLED");

            answer.set("{\"extractPromptVersion\":\"job-extract-v1\",\"rolePromptVersion\":\"role-analysis-v2\"}");
            assertThat(client.promptVersions())
                    .isEqualTo(new JobAiClient.PromptVersions("job-extract-v1", "role-analysis-v2"));
            // Without an answer the versions are unknown, never an error: stored results stay usable.
            status.set(500);
            assertThat(client.promptVersions()).isNull();
            assertThat(disabled.promptVersions()).isNull();
        } finally {
            MDC.remove("requestId");
            server.stop(0);
        }
    }
}
