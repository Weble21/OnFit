package com.doggeon.jobrecommendation.profile;

import java.util.Locale;
import java.util.Map;

public final class SkillNormalizer {
    private static final Map<String, String> ALIASES = Map.of(
            "springboot", "spring boot",
            "postgres", "postgresql",
            "postgresql", "postgresql",
            "amazon web services", "aws"
    );

    private SkillNormalizer() {
    }

    public static String displayName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    public static String normalize(String value) {
        String key = displayName(value).toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }
}
