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

    public static String displayName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    public static String normalize(String value) {
        String key = displayName(value).toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }

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
