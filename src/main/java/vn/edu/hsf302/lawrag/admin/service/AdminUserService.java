package vn.edu.hsf302.lawrag.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;
import vn.edu.hsf302.lawrag.config.TimeConfig;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.repository.AdminAuditRepository;
import vn.edu.hsf302.lawrag.admin.dto.UserAccountView;

/**
 * Admin operations for inspecting accounts, switching roles ({@code USER} / {@code ADMIN}),
 * and enabling or locking user accounts with audit logging.
 */
@Service
public class AdminUserService {

    private static final String SYSTEM_AUTHOR_USERNAME = "ban_quan_tri";
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(TimeConfig.BUSINESS_ZONE);

    private final AppUserRepository users;
    private final AdminAuditRepository audit;

    public AdminUserService(AppUserRepository users, AdminAuditRepository audit) {
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserAccountView> listUsers() {
        return users.findAllByOrderByCreatedAtDesc().stream()
                .map(user -> new UserAccountView(
                        user.getId(),
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getRole(),
                        user.isEnabled(),
                        user.getCreatedAt() == null ? "" : DISPLAY_TIME.format(user.getCreatedAt()),
                        isSystemAccount(user)))
                .toList();
    }

    @Transactional
    public UserRole updateRole(UUID userId, UserRole targetRole, String actorUsername) {
        if (targetRole == null) {
            throw new AdminApprovalException("Vai trò không hợp lệ.");
        }
        AppUser user = requireMutableUser(userId, actorUsername, "thay đổi vai trò của chính mình");
        if (user.getRole() == targetRole) {
            return targetRole;
        }
        if (user.getRole() == UserRole.ADMIN
                && targetRole == UserRole.USER
                && user.isEnabled()
                && users.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new AdminApprovalException("Phải giữ ít nhất một tài khoản ADMIN đang hoạt động.");
        }
        UserRole previous = user.getRole();
        user.setRole(targetRole);
        users.save(user);
        audit.record(
                normalizeActor(actorUsername),
                "CHANGE_USER_ROLE",
                "APP_USER",
                user.getId(),
                "username=" + user.getUsername() + ", from=" + previous + ", to=" + targetRole);
        return targetRole;
    }

    @Transactional
    public boolean toggleEnabled(UUID userId, String actorUsername) {
        AppUser user = requireMutableUser(userId, actorUsername, "tự khóa tài khoản đang đăng nhập");
        boolean nextEnabled = !user.isEnabled();
        if (!nextEnabled
                && user.getRole() == UserRole.ADMIN
                && users.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new AdminApprovalException("Không thể khóa tài khoản ADMIN hoạt động cuối cùng.");
        }
        user.setEnabled(nextEnabled);
        users.save(user);
        audit.record(
                normalizeActor(actorUsername),
                nextEnabled ? "ENABLE_USER" : "DISABLE_USER",
                "APP_USER",
                user.getId(),
                "username=" + user.getUsername() + ", enabled=" + nextEnabled);
        return nextEnabled;
    }

    private AppUser requireMutableUser(UUID userId, String actorUsername, String selfActionLabel) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new AdminApprovalException("Không tìm thấy tài khoản."));
        if (isSystemAccount(user)) {
            throw new AdminApprovalException("Không thể chỉnh sửa tài khoản hệ thống mặc định.");
        }
        if (actorUsername != null && user.getUsername().equalsIgnoreCase(actorUsername.strip())) {
            throw new AdminApprovalException("Không thể " + selfActionLabel + ".");
        }
        return user;
    }

    private static boolean isSystemAccount(AppUser user) {
        return SYSTEM_AUTHOR_USERNAME.equalsIgnoreCase(user.getUsername());
    }

    private static String normalizeActor(String actorUsername) {
        return actorUsername == null || actorUsername.isBlank() ? "admin" : actorUsername.strip();
    }
}

