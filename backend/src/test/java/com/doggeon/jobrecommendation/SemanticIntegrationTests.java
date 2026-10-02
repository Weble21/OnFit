package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.JobPostingStatus;
import com.doggeon.jobrecommendation.domain.Project;
import com.doggeon.jobrecommendation.domain.Skill;
import com.doggeon.jobrecommendation.domain.User;
import com.doggeon.jobrecommendation.domain.UserProfile;
import com.doggeon.jobrecommendation.recommendation.HttpSemanticScoreProvider;
import com.doggeon.jobrecommendation.recommendation.RecommendationCalculator;
import com.doggeon.jobrecommendation.recommendation.SemanticScoreProvider;
import com.doggeon.jobrecommendation.recommendation.SemanticText;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class SemanticIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SemanticScoreProvider provider;
    @Test
    void sendsLimitedTextAndRejectsBadOrSlowAIResponses() throws Exception {
        var profile = new UserProfile(new User("demo@example.invalid", "실명 제외"));
        profile.getTargetRoles().add("백엔드 개발자");
        profile.addSkill(new Skill("Postgres", "postgresql"));
        profile.addProject(new Project("홍길동 프로젝트", "연락처 hello@example.com 010-1234-5678 https://private.example",
                "SpringBoot, Postgres", null, null, null));
        var job = JobPosting.seeded("evaluation", "회사 이름", "문서 AI", "백엔드 개발자", "IT", null,
                "신입", "공개 설명", "문서 분류 API 개발", "서울", LocalDate.of(2027, 12, 31),
                JobPostingStatus.OPEN, List.of("PostgreSQL"), List.of());
        ReflectionTestUtils.setField(job, "id", 7L);
        assertThat(SemanticText.profile(profile, true)).contains("postgresql", "[EMAIL]", "[PHONE]")
                .doesNotContain("실명 제외", "홍길동", "hello@example.com", "010-1234-5678", "private.example");
        assertThat(SemanticText.profile(profile, false)).doesNotContain("연락처", "[EMAIL]");
        assertThat(SemanticText.job(job)).contains("문서 분류 API 개발").doesNotContain("회사 이름", "서울");

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Integer> delayMs = new AtomicReference<>(0);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/semantic-score", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] response = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var provider = new HttpSemanticScoreProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/semantic-score",
                    "private-token", 500, true, JsonMapper.builder().build());
            answer.set("{\"modelVersion\":\"" + HttpSemanticScoreProvider.MODEL_VERSION
                    + "\",\"scores\":[{\"jobId\":7,\"score\":55.50}]}");
            var success = provider.score(profile, List.of(job));
            assertThat(success.modelVersion()).isEqualTo(HttpSemanticScoreProvider.MODEL_VERSION);
            assertThat(success.score(7L)).isEqualByComparingTo("55.50");
            assertThat(body.get()).doesNotContain("hello@example.com", "private.example", "홍길동", "demo@example.invalid");
            answer.set("{\"modelVersion\":\"wrong\",\"scores\":[{\"jobId\":7,\"score\":55.50}]}");
            assertThat(provider.score(profile, List.of(job)).modelVersion()).isEqualTo(SemanticScoreProvider.FALLBACK_VERSION);
            answer.set("{\"modelVersion\":\"" + HttpSemanticScoreProvider.MODEL_VERSION
                    + "\",\"scores\":[{\"jobId\":7,\"score\":101}]}");
            assertThat(provider.score(profile, List.of(job)).score(7L)).isEqualByComparingTo("0");
            delayMs.set(300);
            var shortTimeout = new HttpSemanticScoreProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/semantic-score",
                    "private-token", 100, false, JsonMapper.builder().build());
            assertThat(shortTimeout.score(profile, List.of(job)).modelVersion())
                    .isEqualTo(SemanticScoreProvider.FALLBACK_VERSION);
            delayMs.set(0);
            assertThat(new HttpSemanticScoreProvider(false, "http://127.0.0.1:1/v1/semantic-score", "", 500,
                    false, JsonMapper.builder().build()).score(profile, List.of(job)).modelVersion())
                    .isEqualTo(SemanticScoreProvider.RULES_VERSION);
        } finally {
            server.stop(0);
        }
        var unavailable = new HttpSemanticScoreProvider(true, "http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/semantic-score", "private-token", 150, false, JsonMapper.builder().build());
        assertThat(unavailable.score(profile, List.of(job)).score(7L)).isEqualByComparingTo("0");
    }

    @Test
    void calibratedSemanticScoreOccupiesExistingTwentyPercentWithoutChangingEvidence() {
        var profile = new UserProfile(new User("demo@example.invalid", "가상 사용자"));
        var job = new JobPosting("가상 기업", "개발자", "백엔드 개발자", "API", "서울",
                LocalDate.of(2027, 12, 31), JobPostingStatus.OPEN);
        var rules = new RecommendationCalculator().calculate(profile, job);
        var semantic = new RecommendationCalculator().calculate(profile, job, new BigDecimal("42.55"));
        assertThat(semantic.totalScore()).isEqualByComparingTo("8.51");
        assertThat(semantic.semanticScore()).isEqualByComparingTo("42.55");
        assertThat(semantic.missingSkills()).isEqualTo(rules.missingSkills());
        assertThat(semantic.matchedEvidence()).isEqualTo(rules.matchedEvidence());
    }

    @Test
    @Transactional
    void recommendationApiReusesVersionedScoresAndFallsBackToZero() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String profile = """
                    {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],
                     "skills":["Java","Spring Boot","PostgreSQL"],"certificates":[]}
                    """;
            if (jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email='demo@onfit.local'", Long.class) == 0) {
                mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(profile))
                        .andExpect(status().isCreated());
            } else {
                mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(profile))
                        .andExpect(status().isOk());
            }
            when(provider.score(any(), any())).thenReturn(new SemanticScoreProvider.Result("model-test-v1", Map.of(1L,
                    new BigDecimal("70.00"))));
            var first = mvc.perform(post("/api/recommendations")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(first, "$[*].modelVersion")).contains("model-test-v1");
            assertThat(JsonPath.<List<Number>>read(first, "$[*].semanticScore"))
                    .anySatisfy(value -> assertThat(value.doubleValue()).isEqualTo(70.0));
            var count = jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class);
            mvc.perform(post("/api/recommendations")).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isEqualTo(count);
            when(provider.score(any(), any())).thenReturn(new SemanticScoreProvider.Result(
                    SemanticScoreProvider.FALLBACK_VERSION, Map.of()));
            var fallback = mvc.perform(post("/api/recommendations")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(fallback, "$[*].modelVersion"))
                    .containsOnly(SemanticScoreProvider.FALLBACK_VERSION);
            assertThat(JsonPath.<List<Number>>read(fallback, "$[*].semanticScore"))
                    .allSatisfy(value -> assertThat(value.doubleValue()).isZero());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isGreaterThan(count);
            when(provider.score(any(), any())).thenReturn(new SemanticScoreProvider.Result("model-test-v1", Map.of(1L,
                    new BigDecimal("70.00"))));
            mvc.perform(post("/api/recommendations")).andExpect(status().isCreated());
    }
}
