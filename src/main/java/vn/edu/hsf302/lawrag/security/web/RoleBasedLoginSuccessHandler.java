package vn.edu.hsf302.lawrag.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Routes authenticated users after form login: saved protected URLs win, otherwise
 * {@code ROLE_ADMIN} lands on {@code /admin} and {@code ROLE_USER} lands on {@code /chat}.
 */
@Component
public class RoleBasedLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    public RoleBasedLoginSuccessHandler() {
        setDefaultTargetUrl("/chat");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    protected String determineTargetUrl(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return isAdmin ? "/admin" : "/chat";
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws ServletException, IOException {
        super.onAuthenticationSuccess(request, response, authentication);
    }
}

