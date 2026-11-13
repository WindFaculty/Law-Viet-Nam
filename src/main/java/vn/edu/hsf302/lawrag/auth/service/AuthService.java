package vn.edu.hsf302.lawrag.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;
import vn.edu.hsf302.lawrag.auth.dto.CurrentUserView;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_.-]{3,32}$");

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String rawUsername, String rawDisplayName, String password, String confirmPassword) {
        String username = AppUser.normalizeUsername(rawUsername);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Tên đăng nhập dài 3–32 ký tự, chỉ gồm chữ thường, số và các ký tự _ . -");
        }

        String displayName = rawDisplayName == null ? "" : rawDisplayName.strip();
        if (displayName.length() < 2 || displayName.length() > 100) {
            throw new IllegalArgumentException("Tên hiển thị phải từ 2 đến 100 ký tự.");
        }

        if (password == null || password.length() < 6 || password.length() > 100) {
            throw new IllegalArgumentException("Mật khẩu phải từ 6 đến 100 ký tự.");
        }

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
        }

        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Tên đăng nhập này đã được sử dụng.");
        }

        AppUser user = new AppUser(
                username,
                displayName,
                passwordEncoder.encode(password),
                UserRole.USER);
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUserView> findCurrentUserView(String username) {
        String normalized = AppUser.normalizeUsername(username);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return users.findByUsername(normalized)
                .map(user -> new CurrentUserView(
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getRole() == UserRole.ADMIN));
    }
}

