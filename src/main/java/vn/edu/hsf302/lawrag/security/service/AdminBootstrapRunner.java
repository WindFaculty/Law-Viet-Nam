package vn.edu.hsf302.lawrag.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;
import vn.edu.hsf302.lawrag.security.config.AdminBootstrapProperties;

/**
 * Ensures an enabled {@link UserRole#ADMIN} account exists in environments where
 * bootstrap-admin is enabled and credentials are provided.
 */
@Component
@Order(20)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapProperties properties;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(
            AdminBootstrapProperties properties,
            AppUserRepository users,
            PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        String username = AppUser.normalizeUsername(properties.username());
        if (username.isBlank() || properties.password() == null || properties.password().isBlank()) {
            log.warn("Bootstrap admin enabled, but username or password is blank; skipping admin creation.");
            return;
        }
        if (users.existsByUsername(username) || users.countByRoleAndEnabledTrue(UserRole.ADMIN) > 0) {
            return;
        }
        String displayName = properties.displayName() == null || properties.displayName().isBlank()
                ? "Quản trị viên"
                : properties.displayName().strip();
        AppUser admin = new AppUser(
                username,
                displayName,
                passwordEncoder.encode(properties.password()),
                UserRole.ADMIN);
        users.save(admin);
        log.info("Bootstrapped initial ADMIN account: username={}", username);
    }
}

