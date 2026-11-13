package vn.edu.hsf302.lawrag.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import vn.edu.hsf302.lawrag.security.web.HtmxAwareAccessDeniedHandler;
import vn.edu.hsf302.lawrag.security.web.RoleBasedLoginSuccessHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HtmxAwareAccessDeniedHandler accessDeniedHandler,
            RoleBasedLoginSuccessHandler loginSuccessHandler) throws Exception {

        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/",
                                "/chat/**",
                                "/api/chat/**",
                                "/legal/**",
                                "/login",
                                "/login/",
                                "/register",
                                "/register/",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/favicon.ico",
                                "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/forum", "/forum/*").permitAll()
                        .requestMatchers("/forum/**").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(loginSuccessHandler)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("LAWRAG_SESSION")
                        .permitAll())
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN))
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; "
                                        + "img-src 'self' data:; object-src 'none'; base-uri 'self'; "
                                        + "frame-ancestors 'none'; form-action 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)));

        return http.build();
    }
}
