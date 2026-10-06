package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.doggeon.jobrecommendation.config.RequestLogFilter;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "onfit.seed.enabled=true", "onfit.fixed-date=2026-09-29",
        "spring.security.oauth2.resourceserver.jwt.audiences=onfit-test",
        "logging.file.name=build/test-logs/security.log"})
@ActiveProfiles({"test", "prod"})
@Transactional
class ProductionSecurityTests {
    static final RSAKey key;
    static final HttpServer jwks;
    static final String issuer;
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test-key").build();
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            jwks.start();
            issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void authProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> issuer + "/jwks");
    }
    @AfterAll static void shutdown() { jwks.stop(0); }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RequestLogFilter.class),
                context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    private static String token(String subject, String tokenIssuer, String audience, Instant expiry, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(tokenIssuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expiry))
                .claim("scope", scope).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }
    private static String userToken(String subject) throws Exception {
        return token(subject, issuer, "onfit-test", Instant.now().plusSeconds(300), "");
    }
    private static final String PROFILE = """
            {"targetRoles":["백엔드 개발자"],"preferredLocations":["서울"],"skills":["Java"],"certificates":[]}
            """;

    @Test void validatesSignaturesIssuerAudienceExpiryAndAdminScope() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isOk());
        mvc.perform(get("/api/profiles/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        for (String invalid : new String[]{"Bearer invalid",
                token("alice", "https://wrong.example.test", "onfit-test", Instant.now().plusSeconds(300), ""),
                token("alice", issuer, "other-api", Instant.now().plusSeconds(300), ""),
                token("alice", issuer, "onfit-test", Instant.now().minusSeconds(120), "")}) {
            mvc.perform(get("/api/profiles/me").header("Authorization", invalid)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/admin/jobs/import").header("Authorization", userToken("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/admin/jobs/import").header("Authorization", token("collector", issuer,
                "onfit-test", Instant.now().plusSeconds(300), "jobs:import"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void profilesAndSnapshotsAreIsolatedByAuthenticatedSubject() throws Exception {
        String alice = userToken("alice"), bob = userToken("bob");
        mvc.perform(post("/api/profiles").header("Authorization", alice)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isCreated());
        mvc.perform(get("/api/profiles/me").header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/profiles").header("Authorization", bob)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE.replace("Java", "Python")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/profiles/me").header("Authorization", alice)).andExpect(jsonPath("$.skills[0]").value("Java"));
        mvc.perform(get("/api/profiles/me").header("Authorization", bob)).andExpect(jsonPath("$.skills[0]").value("Python"));
        String body = mvc.perform(post("/api/recommendations").header("Authorization", alice))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(body, "$[0].id").longValue();
        mvc.perform(get("/api/recommendations/{id}", id).header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(get("/api/recommendations/{id}", id).header("Authorization", bob)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email='demo@onfit.local'", Long.class)).isZero();
    }
}
