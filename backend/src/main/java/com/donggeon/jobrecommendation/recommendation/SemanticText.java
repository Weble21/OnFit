package com.doggeon.jobrecommendation.recommendation;

import com.doggeon.jobrecommendation.domain.JobPosting;
import com.doggeon.jobrecommendation.domain.UserProfile;
import com.doggeon.jobrecommendation.profile.SkillNormalizer;
import java.util.stream.Collectors;

/** The only profile/job fields allowed to cross into the private AI service. */
public final class SemanticText {
    private SemanticText() {}

    public static String profile(UserProfile profile, boolean includeProjectDescriptions) {
        StringBuilder text = new StringBuilder();
        append(text, "희망 직무", String.join(", ", profile.getTargetRoles()));
        append(text, "기술", profile.getSkills().stream().map(skill -> skill.getNormalizedName())
                .distinct().sorted().collect(Collectors.joining(", ")));
        profile.getProjects().stream().limit(5).forEach(project -> {
            append(text, "프로젝트 기술", SkillNormalizer.split(project.getTechStack()).stream()
                    .map(SkillNormalizer::normalize).distinct().sorted().collect(Collectors.joining(", ")));
            if (includeProjectDescriptions) append(text, "프로젝트 내용", project.getDescription());
        });
        profile.getExperiences().stream().limit(3).forEach(experience ->
                append(text, "경력 직무", experience.getRoleName()));
        return limit(text.toString(), 2048);
    }

    public static String job(JobPosting job) {
        StringBuilder text = new StringBuilder();
        append(text, "공고 직무", job.getRoleName());
        append(text, "공고 제목", job.getTitle());
        append(text, "업무", job.getResponsibilities());
        append(text, "설명", job.getDescription());
        return limit(text.toString(), 1024);
    }

    private static void append(StringBuilder target, String label, String value) {
        if (value == null || value.isBlank()) return;
        String clean = value.replaceAll("https?://\\S+", "[URL]")
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
                .replaceAll("(?<!\\d)(?:01[016789][- ]?\\d{3,4}[- ]?\\d{4})(?!\\d)", "[PHONE]")
                .replaceAll("(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d)", "[IDENTIFIER]")
                .replaceAll("\\s+", " ").trim();
        if (!clean.isBlank()) target.append(label).append(": ").append(clean).append('\n');
    }

    private static String limit(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maximumCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
