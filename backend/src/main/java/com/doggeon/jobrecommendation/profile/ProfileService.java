package com.doggeon.jobrecommendation.profile;

import com.doggeon.jobrecommendation.domain.Certificate;
import com.doggeon.jobrecommendation.domain.Experience;
import com.doggeon.jobrecommendation.domain.Project;
import com.doggeon.jobrecommendation.domain.Skill;
import com.doggeon.jobrecommendation.domain.User;
import com.doggeon.jobrecommendation.domain.UserProfile;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    // Phase 1 uses one local demo identity. Replace this with the authenticated principal later.
    private static final String DEMO_EMAIL = "demo@onfit.local";

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final EntityManager entityManager;

    public ProfileService(UserRepository userRepository, UserProfileRepository profileRepository,
                          EntityManager entityManager) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public ProfileResponse create(ProfileRequest request) {
        validateDates(request);
        List<Skill> skills = buildSkills(request.skills());
        User user = userRepository.findByEmail(DEMO_EMAIL)
                .orElseGet(() -> userRepository.save(new User(DEMO_EMAIL, "OnFit Demo")));
        if (profileRepository.existsByUserId(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "데모 사용자 프로필이 이미 있습니다.");
        }

        UserProfile profile = new UserProfile(user);
        fillProfile(profile, request, skills);
        profileRepository.saveAndFlush(profile);
        return ProfileResponse.from(profile);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getMine() {
        return ProfileResponse.from(findMine());
    }

    @Transactional
    public ProfileResponse updateMine(ProfileRequest request) {
        validateDates(request);
        List<Skill> skills = buildSkills(request.skills());
        UserProfile profile = findMine();

        // Remove old unique skill keys before inserting replacements in the same transaction.
        profile.getSkills().clear();
        entityManager.flush();

        profile.getTargetRoles().clear();
        profile.getPreferredLocations().clear();
        profile.getCertificates().clear();
        profile.getProjects().clear();
        profile.getExperiences().clear();
        fillProfile(profile, request, skills);
        profile.markUpdated();
        entityManager.flush();
        return ProfileResponse.from(profile);
    }

    private UserProfile findMine() {
        return profileRepository.findByUserEmail(DEMO_EMAIL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "데모 사용자 프로필이 없습니다."));
    }

    private void fillProfile(UserProfile profile, ProfileRequest request, List<Skill> skills) {
        request.targetRoles().stream().map(SkillNormalizer::displayName)
                .forEach(profile.getTargetRoles()::add);
        request.preferredLocations().stream().map(SkillNormalizer::displayName)
                .forEach(profile.getPreferredLocations()::add);
        skills.forEach(profile::addSkill);
        request.certificates().stream().map(SkillNormalizer::displayName)
                .map(name -> new Certificate(name, null, null))
                .forEach(profile::addCertificate);

        for (ProfileRequest.ProjectInput input : optional(request.projects())) {
            profile.addProject(new Project(SkillNormalizer.displayName(input.name()),
                    input.description(), input.techStack(), input.startedOn(),
                    input.endedOn(), input.projectUrl()));
        }
        for (ProfileRequest.ExperienceInput input : optional(request.experiences())) {
            profile.addExperience(new Experience(SkillNormalizer.displayName(input.companyName()),
                    SkillNormalizer.displayName(input.roleName()), input.description(),
                    input.startedOn(), input.endedOn()));
        }
    }

    private List<Skill> buildSkills(List<String> names) {
        Set<String> seen = new HashSet<>();
        List<Skill> result = new ArrayList<>();
        for (String name : names) {
            String displayName = SkillNormalizer.displayName(name);
            String normalizedName = SkillNormalizer.normalize(name);
            if (!seen.add(normalizedName)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "중복된 기술입니다: " + displayName);
            }
            result.add(new Skill(displayName, normalizedName));
        }
        return result;
    }

    private void validateDates(ProfileRequest request) {
        for (ProfileRequest.ProjectInput project : optional(request.projects())) {
            checkDateRange(project.startedOn(), project.endedOn());
        }
        for (ProfileRequest.ExperienceInput experience : optional(request.experiences())) {
            checkDateRange(experience.startedOn(), experience.endedOn());
        }
    }

    private void checkDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private static <T> List<T> optional(List<T> values) {
        return values == null ? List.of() : values;
    }
}
