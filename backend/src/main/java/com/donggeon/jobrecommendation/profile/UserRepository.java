package com.donggeon.jobrecommendation.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import com.donggeon.jobrecommendation.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
