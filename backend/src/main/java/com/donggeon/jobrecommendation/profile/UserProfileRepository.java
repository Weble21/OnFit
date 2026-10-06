package com.donggeon.jobrecommendation.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import com.donggeon.jobrecommendation.domain.UserProfile;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByUserEmail(String email);

    boolean existsByUserId(Long userId);
}
