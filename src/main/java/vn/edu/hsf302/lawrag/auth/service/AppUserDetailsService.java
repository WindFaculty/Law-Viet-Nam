package vn.edu.hsf302.lawrag.auth.service;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String rawUsername) throws UsernameNotFoundException {
        String normalized = AppUser.normalizeUsername(rawUsername);
        if (normalized.isEmpty()) {
            throw new UsernameNotFoundException("Tài khoản không hợp lệ");
        }
        AppUser user = users.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản: " + normalized));

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(!user.isEnabled())
                .build();
    }
}

