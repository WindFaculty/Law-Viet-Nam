package vn.edu.hsf302.lawrag.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import vn.edu.hsf302.lawrag.auth.service.AuthService;
import vn.edu.hsf302.lawrag.auth.dto.CurrentUserView;
import vn.edu.hsf302.lawrag.observability.filter.RequestIdFilter;
import vn.edu.hsf302.lawrag.security.web.AnonymousIdentityFilter;

@ControllerAdvice
public class WebModelAdvice {

    private final AuthService authService;

    public WebModelAdvice(AuthService authService) {
        this.authService = authService;
    }

    @ModelAttribute
    public void addCommonAttributes(Model model, HttpServletRequest request) {
        model.addAttribute("requestId", RequestIdFilter.currentRequestId());
        Object shortAnonId = request.getAttribute(AnonymousIdentityFilter.SHORT_ATTRIBUTE);
        if (shortAnonId != null) {
            model.addAttribute("anonIdShort", shortAnonId);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            CurrentUserView currentUser = authService.findCurrentUserView(authentication.getName())
                    .orElseGet(() -> new CurrentUserView(authentication.getName(), authentication.getName(), isAdmin));
            model.addAttribute("currentUser", currentUser);
        }
    }
}
