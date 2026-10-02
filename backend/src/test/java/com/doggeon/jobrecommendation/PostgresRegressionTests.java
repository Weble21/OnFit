package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doggeon.jobrecommendation.recommendation.RecommendationRetention;
import com.doggeon.jobrecommendation.seed.JobSeedService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Required suite: missing Docker fails this task instead of silently skipping the acceptance gate. */
@Tag("postgres")
@Testcontainers
class PostgresRegressionTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String PROFILE = """
            {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],
             "skills":["Java","Docker","AWS"],"certificates":[]}
            """;

    @ParameterizedTest(name = "DB at V{0} upgrades and passes core APIs")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void freshAndHistoricalDatabasesPassMigrationAndApis(int previousVersion) throws Exception {
        String schema = "regression_v" + previousVersion;
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // Each case owns an isolated schema in the disposable container; no production DB is touched.
        admin.execute("CREATE SCHEMA " + schema);
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));

        if (previousVersion > 0) {
            migrateTo(url, schema, 1);
            insertV1History(jdbc);
            for (int version = 2; version <= previousVersion; version++) {
                migrateTo(url, schema, version);
                if (version == 2) {
                    // A seeded V2 DB already associates this record with the first synthetic posting.
                    jdbc.update("UPDATE job_postings SET seed_key = 'onfit-001' WHERE id = 1");
                }
            }
        }

        // Use actual Spring startup (Flyway + Hibernate validate) for both a new DB and every upgrade.
        // Explicit command-line properties outrank local env vars, including DB_URL and demo dates.
        try (var context = new SpringApplicationBuilder(JobRecommendationBackendApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.default-schema=" + schema,
                "--onfit.seed.enabled=true", "--onfit.fixed-date=2026-09-29",
                "--onfit.recommendation.retention-days=30", "--onfit.recommendation.cleanup-enabled=false")) {
            var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .addFilters(context.getBean(com.doggeon.jobrecommendation.config.RequestLogFilter.class)).build();
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            assertThat(context.getBean(JobSeedService.class).seed()).isZero();
            assertThat(context.getBean(JobSeedService.class).seed()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings WHERE seed_key IS NOT NULL", Long.class))
                    .isEqualTo(40);
            assertThatThrownBy(() -> jdbc.update("UPDATE job_postings SET seed_key='onfit-001' WHERE seed_key='onfit-002'"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            int openCount = previousVersion == 1 ? 37 : 36;
            verifyPagination(mvc, openCount);
            mvc.perform(get("/api/jobs/999999")).andExpect(status().isNotFound());
            mvc.perform(get("/api/recommendations/abc")).andExpect(status().isBadRequest());
            if (previousVersion == 0) {
                mvc.perform(get("/api/profiles/me")).andExpect(status().isNotFound());
                mvc.perform(post("/api/recommendations")).andExpect(status().isNotFound());
                mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                        .andExpect(status().isCreated());
            } else {
                assertThat(jdbc.queryForObject("SELECT industry FROM job_postings WHERE id=1", String.class))
                        .isEqualTo(previousVersion >= 2 ? "클라우드" : "스타트업");
                assertThat(jdbc.queryForObject("SELECT company_size FROM job_postings WHERE id=1", String.class)).isNull();
                assertThat(jdbc.queryForList("SELECT skill_name FROM recommendation_matched_required_skills "
                        + "WHERE recommendation_id=1 ORDER BY sort_order", String.class)).containsExactly("Java", "Docker");
                assertThat(jdbc.queryForList("SELECT sort_order FROM recommendation_matched_preferred_skills "
                        + "WHERE recommendation_id=1 ORDER BY sort_order", Integer.class)).containsExactly(0, 1);
                mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                        .andExpect(status().isOk());
            }
            mvc.perform(get("/api/profiles/me")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.skills.length()").value(3));
            mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                    .andExpect(status().isConflict());
            mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON)
                    .content(PROFILE.replace("\"Java\",\"Docker\",\"AWS\"", "\"AWS\",\"Amazon Web Services\"")))
                    .andExpect(status().isBadRequest());

            String first = mvc.perform(post("/api/recommendations")).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.length()").value(openCount)).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Number>>read(first, "$[*].totalScore")).allSatisfy(score ->
                    assertThat(score.doubleValue()).isBetween(0.0, 80.0));
            long count = jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class);
            String reused = mvc.perform(post("/api/recommendations")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(reused).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isEqualTo(count);
            if (previousVersion > 0) {
                // V3 backfill must make the old snapshot reusable, not just leave a readable row behind.
                var rows = JsonPath.<List<Map<String, Object>>>read(first, "$[*]");
                assertThat(rows).anySatisfy(row -> {
                    assertThat(((Number) row.get("id")).longValue()).isEqualTo(1);
                    assertThat(((Number) row.get("totalScore")).doubleValue()).isEqualTo(46.67);
                });
                mvc.perform(get("/api/recommendations/1")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.missingSkills[0]").value("PostgreSQL"));
            }

            // A changed profile creates new results and keeps the old score/evidence accessible.
            mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON)
                    .content(PROFILE.replace("\"Java\",\"Docker\",\"AWS\"", "\"Java\""))).andExpect(status().isOk());
            String changed = mvc.perform(post("/api/recommendations")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isGreaterThan(count);
            Number oldId = JsonPath.read(first, "$[0].id");
            Number oldScore = JsonPath.read(first, "$[0].totalScore");
            mvc.perform(get("/api/recommendations/{id}", oldId.longValue())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalScore").value(oldScore.doubleValue()));

            // Expired snapshots cannot be read/reused even before the daily physical cleanup runs.
            Number expiredId = JsonPath.read(changed, "$[0].id");
            jdbc.update("UPDATE recommendations SET created_at=? WHERE id=?",
                    Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)), expiredId.longValue());
            mvc.perform(get("/api/recommendations/{id}", expiredId.longValue())).andExpect(status().isNotFound());
            String refreshed = mvc.perform(post("/api/recommendations")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Number>>read(refreshed, "$[*].id")).noneSatisfy(id ->
                    assertThat(id.longValue()).isEqualTo(expiredId.longValue()));
            assertThat(context.getBean(RecommendationRetention.class).purgeExpired()).isEqualTo(1);
            for (String table : List.of("recommendation_evidence", "recommendation_missing_skills",
                    "recommendation_matched_required_skills", "recommendation_matched_preferred_skills")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE recommendation_id=?",
                        Long.class, expiredId.longValue())).isZero();
            }
            assertThat(context.getBean(RecommendationRetention.class).purgeExpired()).isZero();
        }

        // A second application startup must validate again and keep both the seed and all snapshots.
        long beforeRestart = jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class);
        try (var restarted = new SpringApplicationBuilder(JobRecommendationBackendApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.default-schema=" + schema,
                "--onfit.seed.enabled=true", "--onfit.fixed-date=2026-09-29",
                "--onfit.recommendation.retention-days=30", "--onfit.recommendation.cleanup-enabled=false")) {
            assertThat(restarted.getBean(JobSeedService.class).seed()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isEqualTo(beforeRestart);
            var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) restarted).build();
            mvc.perform(post("/api/recommendations")).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations", Long.class)).isEqualTo(beforeRestart);
        }
    }

    @Test
    void productionProfileMigratesWithoutInsertingDemoData() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE SCHEMA production_defaults");
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=production_defaults";
        try (var context = new SpringApplicationBuilder(JobRecommendationBackendApplication.class).run(
                "--spring.profiles.active=prod", "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.flyway.default-schema=production_defaults", "--onfit.recommendation.cleanup-enabled=false",
                "--logging.file.name=build/test-logs/prod.log")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class)).isZero();
            assertThat(context.getEnvironment().getProperty("onfit.fixed-date")).isEmpty();
        }
    }

    private void migrateTo(String url, String schema, int version) {
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schema).target(Integer.toString(version)).load().migrate();
    }

    private void verifyPagination(MockMvc mvc, int total) throws Exception {
        var ids = new java.util.ArrayList<Long>();
        for (int page = 0; page < (total + 6) / 7; page++) {
            String body = mvc.perform(get("/api/jobs").param("page", Integer.toString(page)).param("size", "7"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(total))
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.<List<Number>>read(body, "$.content[*].id").stream().map(Number::longValue).toList());
        }
        assertThat(ids).hasSize(total).doesNotHaveDuplicates().isSorted();
        mvc.perform(get("/api/jobs?page=1000&size=7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.hasNext").value(false));
        for (String query : List.of("page=-1", "size=0", "size=101", "size=abc")) {
            mvc.perform(get("/api/jobs?" + query)).andExpect(status().isBadRequest());
        }
    }

    private void insertV1History(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO app_users(email,display_name) VALUES ('demo@onfit.local','가상 사용자')");
        jdbc.update("INSERT INTO user_profiles(user_id) VALUES (1)");
        jdbc.update("INSERT INTO profile_target_roles VALUES (1,0,'백엔드 개발자')");
        jdbc.update("INSERT INTO profile_preferred_locations VALUES (1,0,'서울')");
        for (String skill : List.of("Java", "Docker", "AWS")) {
            jdbc.update("INSERT INTO skills(profile_id,name,normalized_name) VALUES (1,?,?)", skill, skill.toLowerCase());
        }
        jdbc.update("""
                INSERT INTO job_postings(company_name,title,role_name,company_type,responsibilities,location,deadline,status)
                VALUES ('기존 가상 기업','기존 공고','백엔드 개발자','스타트업','API 개발','서울','2027-12-31','OPEN')
                """);
        jdbc.update("INSERT INTO job_required_skills VALUES (1,0,'Java'),(1,1,'PostgreSQL'),(1,2,'Docker')");
        jdbc.update("INSERT INTO job_preferred_skills VALUES (1,0,'Redis'),(1,1,'AWS'),(1,2,'Docker')");
        jdbc.update("""
                INSERT INTO recommendations(user_id,job_id,total_score,required_score,preferred_score,semantic_score,
                                            experience_score,preference_score)
                VALUES (1,1,46.67,66.67,66.67,0,0,100)
                """);
        jdbc.update("INSERT INTO recommendation_missing_skills VALUES (1,0,'PostgreSQL')");
        List<String> evidence = List.of("필수 기술 일치: Java", "필수 기술 일치: Docker", "우대 기술 일치: AWS",
                "우대 기술 일치: Docker", "희망 직무 일치: 백엔드 개발자", "희망 지역 일치: 서울");
        for (int i = 0; i < evidence.size(); i++) {
            jdbc.update("INSERT INTO recommendation_evidence VALUES (1,?,?)", i, evidence.get(i));
        }
    }
}
