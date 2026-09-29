package com.doggeon.jobrecommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class ProfileApiTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void createsReadsAndReplacesDemoProfile() throws Exception {
        mvc.perform(get("/api/profiles/me"))
                .andExpect(status().isNotFound());

        String original = """
                {
                  "targetRoles": ["백엔드 개발자"],
                  "preferredLocations": ["서울"],
                  "skills": ["Java", "Spring Boot", "PostgreSQL", "AWS", "Docker"],
                  "certificates": ["정보처리기사", "ADsP"],
                  "projects": [{"name":"추천 서비스", "description":"API 개발", "techStack":"Java, Spring Boot"}],
                  "experiences": [{"companyName":"테스트 기업", "roleName":"인턴", "startedOn":"2025-01-01"}]
                }
                """;
        mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/profiles/me"))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.skills[1]").value("Spring Boot"))
                .andExpect(jsonPath("$.projects[0].name").value("추천 서비스"));

        mvc.perform(get("/api/profiles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetRoles[0]").value("백엔드 개발자"))
                .andExpect(jsonPath("$.certificates[1]").value("ADsP"));

        mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isConflict());

        String replacement = """
                {
                  "targetRoles": ["서버 개발자"],
                  "preferredLocations": ["판교"],
                  "skills": ["SpringBoot", "Postgres", "Docker"],
                  "certificates": []
                }
                """;
        mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.length()").value(3))
                .andExpect(jsonPath("$.projects.length()").value(0))
                .andExpect(jsonPath("$.experiences.length()").value(0));

        List<String> normalized = jdbcTemplate.queryForList(
                "SELECT normalized_name FROM skills ORDER BY id", String.class);
        assertEquals(List.of("spring boot", "postgresql", "docker"), normalized);
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT updated_at > created_at FROM user_profiles", Boolean.class));

        mvc.perform(get("/api/profiles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetRoles[0]").value("서버 개발자"))
                .andExpect(jsonPath("$.skills[0]").value("SpringBoot"));

        String duplicateSkills = """
                {
                  "targetRoles": [],
                  "preferredLocations": [],
                  "skills": ["PostgreSQL", "Postgres"],
                  "certificates": []
                }
                """;
        mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(duplicateSkills))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("중복된 기술입니다: Postgres"));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM skills", Integer.class));
    }

    @Test
    void rejectsMissingFieldsAndInvalidDates() throws Exception {
        mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("입력값을 확인해 주세요: ")));
        mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content("{\"skills\": ["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("요청 형식이 올바르지 않습니다.")));

        String invalidDates = """
                {
                  "targetRoles": [],
                  "preferredLocations": [],
                  "skills": [],
                  "certificates": [],
                  "experiences": [{
                    "companyName": "회사", "roleName": "개발자",
                    "startedOn": "2025-12-31", "endedOn": "2025-01-01"
                  }]
                }
                """;
        mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(invalidDates))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("종료일은 시작일보다 빠를 수 없습니다."));
    }
}
