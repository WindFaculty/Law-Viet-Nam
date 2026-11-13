package vn.edu.hsf302.lawrag.admin.dto;

import vn.edu.hsf302.lawrag.auth.entity.UserRole;

import java.util.UUID;

public record UserAccountView(
        UUID id,
        String username,
        String displayName,
        UserRole role,
        boolean enabled,
        String createdAtDisplay,
        boolean systemAccount) {
}
