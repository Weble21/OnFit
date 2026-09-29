package com.doggeon.jobrecommendation.profile;

import com.doggeon.jobrecommendation.domain.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByUserEmail(String email);

    boolean existsByUserId(Long userId);
}
