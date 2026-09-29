package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doggeon.jobrecommendation.profile.UserProfileRepository;
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
                .andExpect(jsonPath("$.length()").value(36))
                .andExpect(jsonPath("$[0].requiredSkills[0]").value("Java"));
        mvc.perform(get("/api/jobs/{id}", closedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/jobs/999999"))
                .andExpect(status().isNotFound());
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
        var repeated = mvc.perform(post("/api/recommendations"))
                .andExpect(status().isCreated())
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
    }
}
