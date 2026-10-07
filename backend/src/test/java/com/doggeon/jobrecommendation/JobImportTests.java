package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

@SpringBootTest(properties = {
        "onfit.ingestion.enabled=true",
        "onfit.ingestion.sources.fixture.allowed-host=jobs.example.test",
        "onfit.ingestion.sources.fixture.terms-url=https://jobs.example.test/terms",
        "onfit.ingestion.sources.fixture.reviewed-on=2026-09-01",
        "onfit.ingestion.sources.fixture.permission-evidence=Synthetic integration test fixture only"})
@ActiveProfiles("test")
@Transactional
class JobImportTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    static String request(String source, String url, String time, String status, String deadline) {
        return """
                {"sourceName":"%s","externalId":"job-1","sourceUrl":"%s","observedAt":"%s",
                 "content":{"companyName":"검증용 회사","title":"개발자","roleName":"백엔드 개발자",
                  "responsibilities":"API 개발","location":"서울","deadline":%s,"status":"%s",
                  "requiredSkills":["Java"],"preferredSkills":[]}}
                """.formatted(source, url, time, deadline == null ? "null" : "\"" + deadline + "\"", status);
    }

    @Test void repeatUpdatesPreserveSourceHistoryAndClosedState() throws Exception {
        String original = request("fixture", "https://jobs.example.test/jobs/1", "2026-09-28T01:00:00Z", "OPEN", null);
        String body = mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.job.origin").value("REAL"))
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(body, "$.job.id").longValue();
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        mvc.perform(get("/api/jobs?size=100")).andExpect(jsonPath("$.totalElements").value(37));
        String closed = original.replace("2026-09-28T01", "2026-09-28T02").replace("\"OPEN\"", "\"CLOSED\"");
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON).content(closed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.id").value(id))
                .andExpect(jsonPath("$.job.status").value("CLOSED"));
        mvc.perform(get("/api/admin/jobs/{id}/revisions", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotJson").value(org.hamcrest.Matchers.containsString("OPEN")))
                .andExpect(jsonPath("$[1].snapshotJson").value(org.hamcrest.Matchers.containsString("CLOSED")));
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/jobs?size=100")).andExpect(jsonPath("$.totalElements").value(36));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings WHERE source_name='fixture'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_source_revisions WHERE job_id=?", Long.class, id)).isEqualTo(2);
    }

    @Test void rejectsUnapprovedSourcesDisguisedUrlsAndInvalidInput() throws Exception {
        for (String url : new String[]{"http://jobs.example.test/1", "https://jobs.example.test.evil.test/1",
                "https://attacker@jobs.example.test/1", "https://jobs.example.test:444/1"}) {
            mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON)
                    .content(request("fixture", url, "2026-09-28T01:00:00Z", "OPEN", null)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON)
                .content(request("unknown", "https://jobs.example.test/1", "2026-09-28T01:00:00Z", "OPEN", null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON)
                .content(request("fixture", "https://jobs.example.test/1", "2026-10-01T01:00:00Z", "OPEN", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void expiredSourceIsStoredAndExcludedFromRecommendations() throws Exception {
        mvc.perform(post("/api/admin/jobs/import").contentType(MediaType.APPLICATION_JSON)
                .content(request("fixture", "https://jobs.example.test/1", "2026-09-28T01:00:00Z", "OPEN", "2026-09-27")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.job.status").value("EXPIRED"));
        mvc.perform(get("/api/jobs?size=100")).andExpect(jsonPath("$.totalElements").value(36));
    }
}
