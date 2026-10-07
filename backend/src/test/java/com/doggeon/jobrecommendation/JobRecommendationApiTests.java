package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donggeon.jobrecommendation.profile.UserProfileRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class JobRecommendationApiTests {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserProfileRepository profiles;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listsOpenJobsAndReturnsDetails() throws Exception {
        Integer closedId = jdbc.queryForObject(
                "SELECT id FROM job_postings WHERE seed_key = 'onfit-038'", Integer.class);
        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(20))
                .andExpect(jsonPath("$.totalElements").value(36))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].industry").value("클라우드"))
                .andExpect(jsonPath("$.content[0].companySize").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].requiredSkills[0]").value("Java"));
        mvc.perform(get("/api/jobs?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(16))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/jobs?page=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        for (String query : new String[]{"page=-1", "size=0", "size=101", "page=abc"}) {
            mvc.perform(get("/api/jobs?" + query)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/jobs/{id}", closedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/jobs/999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("공고 ID는 숫자로 입력해 주세요."));
    }

    @Test
    @Transactional
    void createsAndReadsPersistedRecommendations() throws Exception {
        String body = """
                {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],
                 "skills":["Java","Spring Boot","PostgreSQL","Docker","AWS"],
                 "certificates":[],"projects":[{"name":"추천 서비스","techStack":"Java, Spring Boot, PostgreSQL"}]}
                """;
        if (profiles.findByUserEmail("demo@onfit.local").isPresent()) {
            mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        } else {
            mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }

        var created = mvc.perform(post("/api/recommendations"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(36))
                .andExpect(jsonPath("$[0].semanticScore").value(0))
                .andExpect(jsonPath("$[0].job.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$[0].id");
        Number score = JsonPath.read(created, "$[0].totalScore");
        assertThat(score.doubleValue()).isGreaterThan(0);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recommendations", Integer.class);
        // Nothing changed, so every snapshot is reused and nothing new is created.
        var repeated = mvc.perform(post("/api/recommendations"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(repeated, "$[0].id").longValue()).isEqualTo(id.longValue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Integer.class)).isEqualTo(count);
        mvc.perform(get("/api/recommendations/{id}", id.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.intValue()))
                .andExpect(jsonPath("$.job.requiredSkills").isArray())
                .andExpect(jsonPath("$.matchedRequiredSkills").isArray())
                .andExpect(jsonPath("$.matchedPreferredSkills").isArray())
                .andExpect(jsonPath("$.matchedEvidence").isArray())
                .andExpect(jsonPath("$.missingSkills").isArray());
        mvc.perform(get("/api/recommendations/999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/recommendations/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("추천 ID는 숫자로 입력해 주세요."));
    }
}
