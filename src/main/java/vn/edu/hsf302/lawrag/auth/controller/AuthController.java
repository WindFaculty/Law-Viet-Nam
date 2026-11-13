package vn.edu.hsf302.lawrag.auth.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.service.AuthService;

@Controller
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping({"/login", "/login/"})
    public String login(Authentication authentication) {
        if (isAuthenticated(authentication)) {
            return redirectForRole(authentication);
        }
        return "auth/login";
    }

    @GetMapping({"/register", "/register/"})
    public String registerForm(Authentication authentication) {
        if (isAuthenticated(authentication)) {
            return redirectForRole(authentication);
        }
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@RequestParam(name = "username", defaultValue = "") String username,
                           @RequestParam(name = "displayName", defaultValue = "") String displayName,
                           @RequestParam(name = "password", defaultValue = "") String password,
                           @RequestParam(name = "confirmPassword", defaultValue = "") String confirmPassword,
                           Authentication authentication,
                           Model model) {
        if (isAuthenticated(authentication)) {
            return redirectForRole(authentication);
        }
        try {
            authService.register(username, displayName, password, confirmPassword);
            return "redirect:/login?registered";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("username", AppUser.normalizeUsername(username));
            model.addAttribute("displayName", displayName == null ? "" : displayName.strip());
            return "auth/register";
        }
    }

    private String redirectForRole(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return isAdmin ? "redirect:/admin" : "redirect:/chat";
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}

