package com.donggeon.jobrecommendation.jobanalysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.ProfileActivity;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.recommendation.SemanticText;

/**
 * The profile as it was when an analysis ran. Its hash detects later profile changes, and its items are
 * the only profile evidence the role analysis may cite. Project URLs are not part of it.
 */
public record ProfileSnapshot(List<String> targetRoles, List<String> preferredLocations, List<String> skills,
                              List<CertificateItem> certificates, List<ProjectItem> projects,
                              List<ExperienceItem> experiences, EducationItem education, List<TrainingItem> trainings,
                              List<TrainingItem> activities) {

    public record TrainingItem(Long id, String title, String organization, String description, LocalDate startedOn, LocalDate endedOn) {}
    public record EducationItem(String universityName, String major, String status) {}
    public record CertificateItem(Long id, String name, String score) {}
    public record ProjectItem(Long id, String name, String description, String techStack) {}
    public record ExperienceItem(Long id, String companyName, String roleName, String description,
                                 LocalDate startedOn, LocalDate endedOn) {}
    /** How an item is shown next to a role analysis claim that cites it. */
    public record ItemLabel(String id, String label) {}

    private static final int MAX_ITEM_TEXT = 8000;

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
                                e.getStartedOn(), e.getEndedOn())).toList(),
                profile.getUniversityName() == null ? null : new EducationItem(profile.getUniversityName(),
                        profile.getUniversityMajor(), profile.getUniversityStatus()),
                profile.getActivities().stream().filter(a -> a.getKind() == ProfileActivity.Kind.TRAINING)
                        .map(a -> new TrainingItem(a.getId(), a.getTitle(), a.getOrganization(), a.getDescription(),
                                a.getStartedOn(), a.getEndedOn())).toList(),
                profile.getActivities().stream().filter(a -> a.getKind() == ProfileActivity.Kind.ACTIVITY)
                        .map(a -> new TrainingItem(a.getId(), a.getTitle(), a.getOrganization(), a.getDescription(),
                                a.getStartedOn(), a.getEndedOn())).toList());
    }

    /** Evidence items for the AI service, with contact details masked. */
    public List<JobAiClient.ProfileItem> evidenceItems() {
        List<JobAiClient.ProfileItem> items = new ArrayList<>();
        if (!targetRoles.isEmpty()) items.add(item("roles:all", "roles", "희망 직무: " + String.join(", ", targetRoles)));
        if (!preferredLocations.isEmpty()) items.add(item("locations:all", "locations", "희망 지역: " + String.join(", ", preferredLocations)));
        if (!skills.isEmpty()) items.add(item("skills:all", "skills", "보유 기술: " + String.join(", ", skills)));
        if (education != null) items.add(item("education:university", "education", "대학교: " + education.universityName()
                + optional(" / 전공: ", education.major()) + optional(" / 학적: ", educationStatus(education.status()))));
        if (trainings != null) trainings.stream().limit(10).forEach(training -> items.add(item("training:" + training.id(), "training",
                training.title() + optional(" / 교육기관: ", training.organization())
                        + optional(" / 내용: ", training.description())
                        + (training.startedOn() == null ? "" : " / 시작: " + training.startedOn())
                        + (training.endedOn() == null ? "" : " / 종료: " + training.endedOn()))));
        if (activities != null) activities.forEach(activity -> items.add(item("activity:" + activity.id(), "activity",
                activity.title() + optional(" / 기관: ", activity.organization()) + optional(" / 내용: ", activity.description()))));
        projects.forEach(project -> items.add(item("project:" + project.id(), "project",
                project.name() + optional(" — ", project.description()) + optional(" (기술: ", project.techStack(), ")"))));
        experiences.forEach(experience -> items.add(item("experience:" + experience.id(), "experience",
                experience.companyName() + " / " + experience.roleName() + " (" + experience.startedOn() + " ~ "
                        + (experience.endedOn() == null ? "재직 중" : experience.endedOn()) + ")"
                        + optional(" — ", experience.description()))));
        certificates.forEach(certificate -> items.add(item("certificate:" + certificate.id(),
                "certificate", certificate.name() + optional(" (", certificate.score(), ")"))));
        return items.stream().filter(item -> !item.text().isBlank()).toList();
    }

    public List<ItemLabel> labels() {
        List<ItemLabel> labels = new ArrayList<>();
        if (!targetRoles.isEmpty()) labels.add(new ItemLabel("roles:all", "희망 직무"));
        if (!preferredLocations.isEmpty()) labels.add(new ItemLabel("locations:all", "희망 지역"));
        labels.add(new ItemLabel("skills:all", "보유 기술"));
        if (education != null) labels.add(new ItemLabel("education:university", "대학교 학력"));
        if (trainings != null) trainings.forEach(training -> labels.add(new ItemLabel("training:" + training.id(), "교육 이수 · " + training.title())));
        if (activities != null) activities.forEach(activity -> labels.add(new ItemLabel("activity:" + activity.id(), "활동 · " + activity.title())));
        projects.forEach(project -> labels.add(new ItemLabel("project:" + project.id(), "프로젝트 · " + project.name())));
        experiences.forEach(experience -> labels.add(new ItemLabel("experience:" + experience.id(),
                "경력 · " + experience.companyName() + " " + experience.roleName())));
        certificates.forEach(certificate -> labels.add(new ItemLabel("certificate:" + certificate.id(),
                "자격증 · " + certificate.name())));
        return labels;
    }

    private static String educationStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "ENROLLED" -> "재학";
            case "GRADUATED" -> "졸업";
            case "LEAVE" -> "휴학";
            case "COMPLETED" -> "수료";
            case "WITHDRAWN" -> "중퇴";
            default -> null;
        };
    }

    private static JobAiClient.ProfileItem item(String id, String kind, String text) {
        String clean = SemanticText.redact(text);
        int end = clean.codePointCount(0, clean.length()) <= MAX_ITEM_TEXT ? clean.length()
                : clean.offsetByCodePoints(0, MAX_ITEM_TEXT);
        return new JobAiClient.ProfileItem(id, kind, end == clean.length() ? clean :
                clean.substring(0, clean.offsetByCodePoints(0, MAX_ITEM_TEXT - 20)) + " (프로필 내용 일부 생략)");
    }

    private static String optional(String prefix, String value) {
        return optional(prefix, value, "");
    }

    private static String optional(String prefix, String value, String suffix) {
        return value == null || value.isBlank() ? "" : prefix + value + suffix;
    }
}
