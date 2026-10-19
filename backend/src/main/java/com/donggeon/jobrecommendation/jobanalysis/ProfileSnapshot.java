package com.donggeon.jobrecommendation.jobanalysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.recommendation.SemanticText;

/**
 * The profile as it was when an analysis ran. Its hash detects later profile changes, and its items are
 * the only profile evidence the role analysis may cite. Project URLs are not part of it.
 */
public record ProfileSnapshot(List<String> targetRoles, List<String> preferredLocations, List<String> skills,
                              List<CertificateItem> certificates, List<ProjectItem> projects,
                              List<ExperienceItem> experiences) {

    public record CertificateItem(Long id, String name, String score) {}
    public record ProjectItem(Long id, String name, String description, String techStack) {}
    public record ExperienceItem(Long id, String companyName, String roleName, String description,
                                 LocalDate startedOn, LocalDate endedOn) {}
    /** How an item is shown next to a role analysis claim that cites it. */
    public record ItemLabel(String id, String label) {}

    private static final int MAX_ITEM_TEXT = 2000;

    public static ProfileSnapshot of(UserProfile profile) {
        return new ProfileSnapshot(
                List.copyOf(profile.getTargetRoles()),
                List.copyOf(profile.getPreferredLocations()),
                profile.getSkills().stream().map(Skill::getName).toList(),
                profile.getCertificates().stream()
                        .map(c -> new CertificateItem(c.getId(), c.getName(), c.getScore())).toList(),
                profile.getProjects().stream()
                        .map(p -> new ProjectItem(p.getId(), p.getName(), p.getDescription(), p.getTechStack())).toList(),
                profile.getExperiences().stream()
                        .map(e -> new ExperienceItem(e.getId(), e.getCompanyName(), e.getRoleName(), e.getDescription(),
                                e.getStartedOn(), e.getEndedOn())).toList());
    }

    /** Evidence items for the AI service, with contact details masked. */
    public List<JobAiClient.ProfileItem> evidenceItems() {
        List<JobAiClient.ProfileItem> items = new ArrayList<>();
        if (!targetRoles.isEmpty()) items.add(item("roles:all", "roles", "희망 직무: " + String.join(", ", targetRoles)));
        if (!skills.isEmpty()) items.add(item("skills:all", "skills", "보유 기술: " + String.join(", ", skills)));
        projects.stream().limit(20).forEach(project -> items.add(item("project:" + project.id(), "project",
                project.name() + optional(" — ", project.description()) + optional(" (기술: ", project.techStack(), ")"))));
        experiences.stream().limit(20).forEach(experience -> items.add(item("experience:" + experience.id(), "experience",
                experience.companyName() + " / " + experience.roleName() + " (" + experience.startedOn() + " ~ "
                        + (experience.endedOn() == null ? "재직 중" : experience.endedOn()) + ")"
                        + optional(" — ", experience.description()))));
        certificates.stream().limit(15).forEach(certificate -> items.add(item("certificate:" + certificate.id(),
                "certificate", certificate.name() + optional(" (", certificate.score(), ")"))));
        return items.stream().filter(item -> !item.text().isBlank()).toList();
    }

    public List<ItemLabel> labels() {
        List<ItemLabel> labels = new ArrayList<>();
        labels.add(new ItemLabel("roles:all", "희망 직무"));
        labels.add(new ItemLabel("skills:all", "보유 기술"));
        projects.forEach(project -> labels.add(new ItemLabel("project:" + project.id(), "프로젝트 · " + project.name())));
        experiences.forEach(experience -> labels.add(new ItemLabel("experience:" + experience.id(),
                "경력 · " + experience.companyName() + " " + experience.roleName())));
        certificates.forEach(certificate -> labels.add(new ItemLabel("certificate:" + certificate.id(),
                "자격증 · " + certificate.name())));
        return labels;
    }

    private static JobAiClient.ProfileItem item(String id, String kind, String text) {
        String clean = SemanticText.redact(text);
        int end = clean.codePointCount(0, clean.length()) <= MAX_ITEM_TEXT ? clean.length()
                : clean.offsetByCodePoints(0, MAX_ITEM_TEXT);
        return new JobAiClient.ProfileItem(id, kind, clean.substring(0, end));
    }

    private static String optional(String prefix, String value) {
        return optional(prefix, value, "");
    }

    private static String optional(String prefix, String value, String suffix) {
        return value == null || value.isBlank() ? "" : prefix + value + suffix;
    }
}
