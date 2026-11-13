package com.donggeon.jobrecommendation.recommendation;

import java.util.List;
import java.util.Set;

/** Conservative related evidence for broad Web requirements, not an equivalence between technologies. */
public final class WebSkillMatcher {
    private static final Set<String> WEB_SKILLS = Set.of("html", "html5", "css", "css3", "javascript", "typescript",
            "react", "vue", "angular", "next.js", "node.js", "express", "spring", "spring boot", "jsp",
            "django", "flask", "fastapi", "asp.net", "php", "jquery");
    private WebSkillMatcher() {}

    public static List<String> related(String requirement, Set<String> skills) {
        String compact = requirement.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (skills.contains(requirement)) return List.of();
        // Spring Boot is built on Spring; show this as related evidence, not the same technology.
        if (compact.equals("spring") && skills.contains("spring boot")) return List.of("spring boot");
        if (!Set.of("web기술", "웹기술", "web개발", "웹개발", "webtechnology", "webtechnologies").contains(compact))
            return List.of();
        var found = skills.stream().filter(WEB_SKILLS::contains).sorted().toList();
        // Two aliases or frameworks from the same area do not establish broader Web capability.
        long groups = found.stream().map(WebSkillMatcher::group).distinct().count();
        return groups >= 2 ? found : List.of();
    }

    private static String group(String skill) {
        if (Set.of("html", "html5").contains(skill)) return "markup";
        if (Set.of("css", "css3").contains(skill)) return "style";
        if (Set.of("javascript", "typescript").contains(skill)) return "language";
        if (Set.of("react", "vue", "angular", "next.js", "jquery").contains(skill)) return "frontend";
        return "server";
    }

    public static boolean matches(String requirement, Set<String> skills) {
        return skills.contains(requirement) || !related(requirement, skills).isEmpty();
    }
}
