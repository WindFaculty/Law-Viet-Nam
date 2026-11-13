package vn.edu.hsf302.lawrag.auth.dto;

/** Lightweight view of the authenticated user exposed to templates via {@code WebModelAdvice}. */
public record CurrentUserView(
        String username,
        String displayName,
        boolean admin
) {
}

