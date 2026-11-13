package vn.edu.hsf302.lawrag.chat.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.edu.hsf302.lawrag.security.web.AnonymousIdentityFilter;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;

/**
 * Resolves the caller identity used by chat controllers. Authenticated accounts
 * take precedence over the server-managed anonymous cookie issued by
 * {@link AnonymousIdentityFilter}.
 */
public final class ChatOwnerResolver {

    private ChatOwnerResolver() {
    }

    public static SessionOwner current(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {
            return SessionOwner.account(authentication.getName());
        }
        Object anonId = request.getAttribute(AnonymousIdentityFilter.ATTRIBUTE);
        if (anonId == null) {
            throw new IllegalStateException("Anonymous identity is not available");
        }
        return SessionOwner.anonymous(anonId.toString());
    }
}
