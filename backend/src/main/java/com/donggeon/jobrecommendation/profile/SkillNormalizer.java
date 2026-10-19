package com.donggeon.jobrecommendation.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class SkillNormalizer {
    // The frontend keeps a copy of this table; frontend/tests/skills.test.mjs fails when they drift apart.
    private static final Map<String, String> ALIASES = loadAliases();
    // "/" is not a separator so that names such as CI/CD stay whole. Must match splitSkills in frontend/src/data.js.
    private static final Pattern SEPARATORS = Pattern.compile("[,，;\\r\\n]");

    private SkillNormalizer() {
    }

    // 공백 정리
    public static String displayName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    // 사용할 이름 통일 - 소문자로 만들고 별칭 확인
    public static String normalize(String value) {
        String key = displayName(value).toLowerCase(Locale.ROOT);
        // ALIASES 는 대응표 : k8s-> kubernetes, postgres -> postgresql 등등
        return ALIASES.getOrDefault(key, key);
    }

    // 한 문장에 적힌 기술들 목록으로 분리
    /** Splits a free-text tech stack such as "Java, Spring Boot" into skill names. */
    public static List<String> split(String stack) {
        if (stack == null) {
            return List.of();
        }
        return SEPARATORS.splitAsStream(stack).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static Map<String, String> loadAliases() {
        try (InputStream input = SkillNormalizer.class.getResourceAsStream("/skill-aliases.json")) {
            return Map.copyOf(JsonMapper.builder().build().readValue(input, new TypeReference<Map<String, String>>() { }));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
