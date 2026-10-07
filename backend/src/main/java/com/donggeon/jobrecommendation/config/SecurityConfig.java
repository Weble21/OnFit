package com.donggeon.jobrecommendation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Map;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean
    @Profile("test & !prod & !auth")
    SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    @Profile("!test | prod | auth")
    SecurityFilterChain productionSecurity(HttpSecurity http, JsonMapper json) throws Exception {
        // The API accepts bearer tokens only; no cookie/session authentication is used.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/jobs", "/api/jobs/*").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("SCOPE_jobs:import")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> problem(response, json, 401))
                        .accessDeniedHandler((request, response, error) -> problem(response, json, 403)))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, error) -> problem(response, json, 401))
                        .accessDeniedHandler((request, response, error) -> problem(response, json, 403))).build();
    }

    private static void problem(HttpServletResponse response, JsonMapper json, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write(json.writeValueAsString(Map.of("type", "about:blank", "status", status,
                "title", status == 401 ? "인증 필요" : "접근 거부",
                "detail", status == 401 ? "로그인이 필요합니다." : "이 작업을 수행할 권한이 없습니다.",
                "requestId", MDC.get("requestId") == null ? "" : MDC.get("requestId"))));
    }
}
