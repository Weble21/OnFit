package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donggeon.jobrecommendation.JobRecommendationBackendApplication;
import com.donggeon.jobrecommendation.recommendation.RecommendationRetention;
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
import org.testcontainers.utility.MountableFile;
import org.testcontainers.images.builder.Transferable;

/** Required suite: missing Docker fails this task instead of silently skipping the acceptance gate. */
@Tag("postgres")
@Testcontainers
class PostgresRegressionTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String PROFILE = """
            {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],
             "skills":["Java","Docker","AWS"],
             "certificates":[{"name":"정보처리기사","category":"TECHNICAL","acquiredOn":"2024-06-14","score":"최종합격"}]}
            """;

    @ParameterizedTest(name = "DB at V{0} upgrades and passes core APIs")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
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
                "--spring.profiles.active=test", "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.default-schema=" + schema,
                "--onfit.fixed-date=2026-09-29",
                "--onfit.recommendation.retention-days=30", "--onfit.recommendation.cleanup-enabled=false")) {
            var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .addFilters(context.getBean(com.donggeon.jobrecommendation.config.RequestLogFilter.class)).build();
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
            // V13 adds user-owned analyses without touching earlier job or recommendation rows.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_analyses", Long.class)).isZero();
            // V12: certificates kept from older versions become OTHER, and the issuer column is gone.
            assertThat(jdbc.queryForList("SELECT category FROM certificates", String.class))
                    .containsExactlyElementsOf(previousVersion > 0 ? List.of("OTHER") : List.of());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? "
                    + "AND table_name='certificates' AND column_name='issuer'", Integer.class, schema)).isZero();
            assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            // Startup must preserve historical rows without adding automatic job seeds.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Long.class))
                    .isEqualTo(previousVersion > 0 ? 1L : 0L);
            if (previousVersion > 0) {
                assertThat(jdbc.queryForList("SELECT skill_name FROM recommendation_matched_required_skills "
                        + "WHERE recommendation_id=1 ORDER BY sort_order", String.class)).containsExactly("Java", "Docker");
                assertThat(jdbc.queryForObject("SELECT total_score FROM recommendations WHERE id=1", java.math.BigDecimal.class))
                        .isEqualByComparingTo("46.67");
                mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                        .andExpect(status().isOk());
            } else {
                mvc.perform(get("/api/profiles/me")).andExpect(status().isNotFound());
                mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                        .andExpect(status().isCreated());
            }
            mvc.perform(get("/api/profiles/me")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.skills.length()").value(3))
                    .andExpect(jsonPath("$.certificates[0].score").value("최종합격"));
            mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                    .andExpect(status().isConflict());
            mvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON)
                    .content(PROFILE.replace("\"Java\",\"Docker\",\"AWS\"", "\"AWS\",\"Amazon Web Services\"")))
                    .andExpect(status().isBadRequest());
            for (String route : List.of("/api/jobs", "/api/alerts/me", "/api/recommendations")) {
                mvc.perform(get(route)).andExpect(status().isNotFound());
            }
        }
    }

    private void migrateTo(String url, String schema, int version) {
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schema).target(Integer.toString(version)).load().migrate();
    }

    @Test
    void backupAndRestoreScriptsWorkAgainstDisposablePostgres() throws Exception {
        assertThat(postgres.execInContainer("createdb", "-U", postgres.getUsername(), "backup_fixture").getExitCode()).isZero();
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/backup_fixture");
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        jdbc.update("""
                INSERT INTO job_postings(company_name,title,role_name,responsibilities,location,status)
                VALUES ('Backup fixture','Backup job','Developer','API','Seoul','OPEN')
                """);
        assertThat(postgres.execInContainer("mkdir", "-p", "/ops", "/run/secrets", "/backups").getExitCode()).isZero();
        postgres.copyFileToContainer(MountableFile.forHostPath("../infra/backup.sh"), "/ops/backup.sh");
        postgres.copyFileToContainer(MountableFile.forHostPath("../infra/restore-check.sh"), "/ops/restore-check.sh");
        postgres.copyFileToContainer(Transferable.of(postgres.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600),
                "/run/secrets/DB_PASSWORD");
        var backup = postgres.execInContainer("env", "PGUSER=" + postgres.getUsername(), "PGDATABASE=backup_fixture",
                "sh", "/ops/backup.sh");
        assertThat(backup.getExitCode()).withFailMessage(backup.getStderr()).isZero();
        assertThat(backup.getStdout()).contains("backup_success");
        String file = postgres.execInContainer("find", "/backups/daily", "-name", "onfit-*.dump").getStdout().trim();
        var restored = postgres.execInContainer("env", "PGUSER=" + postgres.getUsername(), "PGDATABASE=backup_fixture",
                "sh", "/ops/restore-check.sh", file);
        assertThat(restored.getExitCode()).withFailMessage(restored.getStderr()).isZero();
        assertThat(restored.getStdout()).contains("restore_archive_check_success", "postings", "recommendations");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_database WHERE datname LIKE 'onfit_restore_check_%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Long.class)).isEqualTo(1);
    }

    private void insertV1History(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO app_users(email,display_name) VALUES ('demo@onfit.local','가상 사용자')");
        jdbc.update("INSERT INTO user_profiles(user_id) VALUES (1)");
        jdbc.update("INSERT INTO profile_target_roles VALUES (1,0,'백엔드 개발자')");
        jdbc.update("INSERT INTO profile_preferred_locations VALUES (1,0,'서울')");
        jdbc.update("INSERT INTO certificates(profile_id,name,issuer) VALUES (1,'정보처리기사','한국산업인력공단')");
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
