package com.donggeon.jobrecommendation.jobanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import com.donggeon.jobrecommendation.domain.*;
import org.junit.jupiter.api.Test;

class ProfileSnapshotTests {
    @Test
    void laterProjectsAndLongDescriptionsAreNotLostBeforeEvaluation() {
        var profile = new UserProfile(new User("complete@example.invalid", "Test"));
        for (int i = 0; i < 20; i++) profile.addProject(new Project("프로젝트 " + i, "설명", "Java", null, null, null));
        profile.addProject(new Project("마지막 프로젝트", "구현 내용 ".repeat(400) + "Spring API 구현", "Spring Boot", null, null, null));
        profile.addActivity(new ProfileActivity(ProfileActivity.Kind.ACTIVITY, "개발 동아리", "학교", "팀 API 설계", null, null));
        var items = ProfileSnapshot.of(profile).evidenceItems();
        assertThat(items.stream().filter(item -> item.kind().equals("project")).count()).isEqualTo(21);
        assertThat(items.stream().filter(item -> item.text().startsWith("마지막 프로젝트")).findFirst().orElseThrow().text())
                .contains("Spring API 구현", "Spring Boot");
        assertThat(items.stream().filter(item -> item.kind().equals("activity")).findFirst().orElseThrow().text())
                .contains("팀 API 설계");
    }
}
