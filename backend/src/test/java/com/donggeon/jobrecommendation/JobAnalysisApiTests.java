package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.donggeon.jobrecommendation.domain.JobAnalysis;
import com.donggeon.jobrecommendation.domain.JobAnalysisDraft;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.jobanalysis.JobAiClient;
import com.donggeon.jobrecommendation.jobanalysis.JobAiException;
import com.donggeon.jobrecommendation.jobanalysis.JobAnalysisDraftRepository;
import com.donggeon.jobrecommendation.jobanalysis.JobAnalysisRepository;
import com.donggeon.jobrecommendation.jobanalysis.StructuredPosting;
import com.donggeon.jobrecommendation.jobanalysis.StructuredPosting.Item;
import com.donggeon.jobrecommendation.jobanalysis.StructuredPosting.TextField;
import com.donggeon.jobrecommendation.profile.UserRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobAnalysisApiTests {
    private static final String TEXT = """
            모노랩 | 백엔드 개발자 (신입)
            근무지: 서울 강남구
            자격요건
            - Java, Spring Boot, Postgres 경험
            우대사항
            - Redis 캐시 경험""";

    @Autowired WebApplicationContext context;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JobAnalysisRepository analyses;
    @Autowired JobAnalysisDraftRepository drafts;
    @MockitoBean JobAiClient ai;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        saveProfile("""
                {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],
                 "skills":["Java","Spring Boot","PostgreSQL","Docker"],"certificates":[],
                 "projects":[{"name":"추천 서비스","description":"Spring Boot API 설계. 문의 dev@example.com",
                              "techStack":"Java, Spring Boot","projectUrl":"https://private.example"}]}
                """, true);
        when(ai.extract(anyString())).thenReturn(new JobAiClient.Extraction(posting(
                List.of(item("Java"), item("Spring Boot"), item("Postgres")), List.of(item("Redis"))),
                "claude-opus-5-5", "job-extract-v1"));
        when(ai.analyzeRole(anyString(), any(), anyList(), any())).thenReturn(role());
    }

    @Test
    void scoresStructuredPostingWithExistingCalculatorAndStoresVersions() throws Exception {
        String body = mvc.perform(analyze("TEXT", TEXT, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputType").value("TEXT"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.posting.requiredSkills[2].value").value("Postgres"))
                // Postgres matches the profile's PostgreSQL through the alias table.
                .andExpect(jsonPath("$.score.required").value(100.0))
                .andExpect(jsonPath("$.score.matchedRequired.length()").value(3))
                .andExpect(jsonPath("$.score.preferred").value(0.0))
                .andExpect(jsonPath("$.score.experience").value(66.67))
                .andExpect(jsonPath("$.score.preference").value(100.0))
                // 100*.35 + 0*.20 + 0*.20 + 66.67*.15 + 100*.10; the semantic model is off, so 80 is the ceiling.
                .andExpect(jsonPath("$.score.total").value(55.0))
                .andExpect(jsonPath("$.score.maxTotal").value(80.0))
                .andExpect(jsonPath("$.score.semanticStatus").value("DISABLED"))
                .andExpect(jsonPath("$.roleStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.roleAnalysis.summary.text").value("커머스 API 백엔드 직무"))
                .andExpect(jsonPath("$.profileItems[2].label").value("프로젝트 · 추천 서비스"))
                .andExpect(jsonPath("$.versions.extractionModel").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.versions.calculator").value("rules-v1/weights-default/adapter-v1"))
                .andExpect(jsonPath("$.versions.semanticModel").value("rules-only-v1"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(body).path("id").asLong();
        mvc.perform(get("/api/job-analysis/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score.total").value(55.0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobAiClient.ProfileItem>> items = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<JobAiClient.ScoreFacts> facts = ArgumentCaptor.forClass(JobAiClient.ScoreFacts.class);
        verify(ai).analyzeRole(anyString(), any(), items.capture(), facts.capture());
        String sent = items.getValue().toString();
        assertThat(sent).contains("project:", "[EMAIL]").doesNotContain("dev@example.com", "private.example");
        assertThat(facts.getValue().matchedRequired()).containsExactly("Java", "Spring Boot", "Postgres");
        assertThat(jdbc.queryForObject("select source_text from job_analyses where id = ?", String.class, id))
                .isEqualTo(TEXT.trim());
    }

    @Test
    void sameConfirmedTextAndProfileReturnTheStoredAnalysis() throws Exception {
        long first = id(mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk()));
        long again = id(mvc.perform(analyze("TEXT", "  " + TEXT.replace("\n", "\r\n") + "\n", null, null))
                .andExpect(status().isOk()));
        assertThat(again).isEqualTo(first);
        verify(ai, times(1)).extract(anyString());

        // A saved profile makes the old result stale and a new request recomputes against the new profile.
        saveProfile("""
                {"targetRoles":["백엔드 개발자"],"preferredLocations":["부산"],"skills":["Java"],"certificates":[]}
                """, false);
        mvc.perform(get("/api/job-analysis/" + first)).andExpect(jsonPath("$.stale").value(true));
        long recomputed = id(mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false)));
        assertThat(recomputed).isNotEqualTo(first);
        verify(ai, times(2)).extract(anyString());
    }

    @Test
    void storedAnalysisFromAnOlderPromptIsRecomputed() throws Exception {
        when(ai.promptVersions()).thenReturn(new JobAiClient.PromptVersions("job-extract-v1", "role-analysis-v1"));
        long first = id(mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk()));
        assertThat(id(mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk()))).isEqualTo(first);
        verify(ai, times(1)).extract(anyString());

        // The AI service now runs a newer role prompt, so the same text and profile are analyzed again.
        when(ai.promptVersions()).thenReturn(new JobAiClient.PromptVersions("job-extract-v1", "role-analysis-v2"));
        long recomputed = id(mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk()));
        assertThat(recomputed).isNotEqualTo(first);
        verify(ai, times(2)).extract(anyString());
    }

    @Test
    void postingWithoutRecognizedRequirementsIsNeverAPerfectFit() throws Exception {
        when(ai.extract(anyString())).thenReturn(new JobAiClient.Extraction(new StructuredPosting(
                new TextField(null, null), new TextField(null, null), new TextField(null, null),
                new TextField(null, null), new TextField(null, null), List.of(), List.of(), List.of(), List.of(), List.of()),
                "claude-opus-5-5", "job-extract-v1"));
        mvc.perform(analyze("TEXT", "성실하고 협업을 잘하는 동료를 찾습니다. 함께 성장해요.", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.noRequirements").value(true))
                .andExpect(jsonPath("$.score.required").value(0.0))
                .andExpect(jsonPath("$.score.preferred").value(0.0))
                .andExpect(jsonPath("$.score.total").value(0.0));
    }

    @Test
    void fileInputAnalyzesOnlyTheConfirmedTextOfTheUsersOwnDraft() throws Exception {
        User me = users.findByEmail("demo@onfit.local").orElseThrow();
        User other = users.save(new User("other@onfit.local", "Other"));
        long mine = drafts.save(new JobAnalysisDraft(me, "OCR 원문", "OCR", 1, false)).getId();
        long theirs = drafts.save(new JobAnalysisDraft(other, "OCR 원문", "OCR", 1, false)).getId();
        String edited = TEXT + "\n(사용자가 수정함)";

        mvc.perform(analyze("FILE", edited, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("파일에서 추출한 텍스트를 확인한 뒤 분석해 주세요."));
        // Confirmed TEXT, then edited it without confirming again.
        mvc.perform(analyze("FILE", edited, mine, sha256(TEXT.trim()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("확인한 뒤 텍스트가 바뀌었습니다. 수정한 텍스트를 다시 확인해 주세요."));
        mvc.perform(analyze("FILE", edited, theirs, sha256(edited))).andExpect(status().isNotFound());
        verify(ai, never()).extract(anyString());

        mvc.perform(analyze("FILE", edited, mine, sha256(edited))).andExpect(status().isOk())
                .andExpect(jsonPath("$.inputType").value("FILE"));
        assertThat(jdbc.queryForObject("select draft_id from job_analyses", Long.class)).isEqualTo(mine);
    }

    @Test
    void anotherUsersAnalysisLooksMissing() throws Exception {
        User other = users.save(new User("other@onfit.local", "Other"));
        var scoring = new JobAnalysis.Scoring("v", JobAnalysis.SemanticStatus.DISABLED, "rules-only-v1",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                "{\"matchedRequired\":[],\"matchedPreferred\":[],\"missing\":[],\"evidence\":[],\"noRequirements\":true}");
        long theirs = analyses.save(new JobAnalysis(other, JobAnalysis.InputType.TEXT, null, TEXT, "h", "{}", "p",
                "{}", "m", "p", scoring)).getId();
        mvc.perform(get("/api/job-analysis/" + theirs)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("분석 결과를 찾을 수 없습니다."));
        mvc.perform(post("/api/job-analysis/" + theirs + "/role-analysis")).andExpect(status().isNotFound());
        verify(ai, never()).analyzeRole(anyString(), any(), anyList(), any());
        mvc.perform(get("/api/job-analysis/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("분석 ID는 숫자로 입력해 주세요."));
    }

    @Test
    void failedRoleAnalysisKeepsTheScoreAndOnlyTheExplanationIsRetried() throws Exception {
        when(ai.analyzeRole(anyString(), any(), anyList(), any())).thenThrow(new JobAiException("LLM_TIMEOUT"));
        String first = mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roleStatus").value("FAILED"))
                .andExpect(jsonPath("$.roleError").value("AI 분석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.roleAnalysis").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.score.total").value(55.0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(first).path("id").asLong();

        mvc.perform(post("/api/job-analysis/" + id + "/role-analysis")).andExpect(status().isGatewayTimeout());
        doReturn(role()).when(ai).analyzeRole(anyString(), any(), anyList(), any());
        String retried = mvc.perform(post("/api/job-analysis/" + id + "/role-analysis")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roleStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.roleError").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(retried).path("score")).isEqualTo(json.readTree(first).path("score"));
        verify(ai, times(1)).extract(anyString());
    }

    @Test
    void failedStructuringStoresNothingAndInputIsValidated() throws Exception {
        when(ai.extract(anyString())).thenThrow(new JobAiException("LLM_TIMEOUT"));
        mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value("AI 분석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."));
        doThrow(new JobAiException("LLM_DISABLED")).when(ai).extract(anyString());
        mvc.perform(analyze("TEXT", TEXT, null, null)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select count(*) from job_analyses", Integer.class)).isZero();

        mvc.perform(analyze("TEXT", "  짧은 공고  ", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("공고 텍스트를 20자 이상 입력해 주세요."));
        mvc.perform(analyze("TEXT", "x".repeat(15_001), null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("공고 텍스트 입력값을 확인해 주세요."));
        mvc.perform(post("/api/job-analysis/text").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + "가".repeat(30) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("입력 방식 입력값을 확인해 주세요."));
    }

    private org.springframework.test.web.servlet.RequestBuilder analyze(String type, String text, Long draftId, String hash) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("inputType", type, "text", text));
        if (draftId != null) body.put("draftId", draftId);
        if (hash != null) body.put("confirmedTextHash", hash);
        return post("/api/job-analysis/text").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private void saveProfile(String body, boolean create) throws Exception {
        mvc.perform((create ? post("/api/profiles") : put("/api/profiles/me"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();
    }

    private static StructuredPosting posting(List<Item> required, List<Item> preferred) {
        return new StructuredPosting(new TextField("모노랩", "모노랩"), new TextField("백엔드 개발자 (신입)", "백엔드 개발자 (신입)"),
                new TextField("백엔드 개발자", "백엔드 개발자"), new TextField("신입", "(신입)"),
                new TextField("서울 강남구", "근무지: 서울 강남구"), List.of(), required, preferred, List.of(), List.of());
    }

    private static Item item(String skill) {
        return new Item(skill, skill + " 경험");
    }

    private JobAiClient.RoleResult role() {
        return new JobAiClient.RoleResult(json.readTree("""
                {"summary":{"text":"커머스 API 백엔드 직무","evidence":["백엔드 개발자"]},"responsibilities":[],
                 "competencies":[],"keywords":[],"strengths":[],"unverified":[],"highlights":[]}
                """), "claude-opus-5-5", "role-analysis-v1");
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
