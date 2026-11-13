package vn.edu.hsf302.lawrag.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByCreatedAtDesc();

    long countByRoleAndEnabledTrue(UserRole role);
}

