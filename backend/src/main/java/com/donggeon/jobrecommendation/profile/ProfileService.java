package com.donggeon.jobrecommendation.profile;

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

import com.donggeon.jobrecommendation.domain.Certificate;
import com.donggeon.jobrecommendation.domain.Experience;
import com.donggeon.jobrecommendation.domain.Project;
import com.donggeon.jobrecommendation.domain.Skill;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;

@Service
public class ProfileService {
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final EntityManager entityManager;

    public ProfileService(UserRepository userRepository, UserProfileRepository profileRepository,
                          EntityManager entityManager, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.entityManager = entityManager;
        this.currentUser = currentUser;
    }

    @Transactional
    public ProfileResponse create(ProfileRequest request) {
        validateDates(request);
        List<Skill> skills = buildSkills(request.skills());
        String identity = currentUser.identity();
        User user = userRepository.findByEmail(identity)
                .orElseGet(() -> userRepository.save(new User(identity, "OnFit User")));
        if (profileRepository.existsByUserId(user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "사용자 프로필이 이미 있습니다.");
        }

        UserProfile profile = new UserProfile(user);
        fillProfile(profile, request, skills);
        profileRepository.saveAndFlush(profile);
        return ProfileResponse.from(profile);
    }

    // 이 트랜잭션에서는 조회만 함 - DB를 수정할 필요가 없기 때문
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
        return profileRepository.findByUserEmail(currentUser.identity())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "사용자 프로필이 없습니다."));
    }

    private void fillProfile(UserProfile profile, ProfileRequest request, List<Skill> skills) {
        request.targetRoles().stream().map(SkillNormalizer::displayName)
                .forEach(profile.getTargetRoles()::add);
        request.preferredLocations().stream().map(SkillNormalizer::displayName)
                .forEach(profile.getPreferredLocations()::add);
        skills.forEach(profile::addSkill);
        for (ProfileRequest.CertificateInput input : request.certificates()) {
            profile.addCertificate(new Certificate(SkillNormalizer.displayName(input.name()),
                    optionalText(input.issuer()), input.acquiredOn(), optionalText(input.score())));
        }

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

    // Blank optional text is stored as NULL, not as an empty string.
    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : SkillNormalizer.displayName(value);
    }

    private static <T> List<T> optional(List<T> values) {
        return values == null ? List.of() : values;
    }
}
