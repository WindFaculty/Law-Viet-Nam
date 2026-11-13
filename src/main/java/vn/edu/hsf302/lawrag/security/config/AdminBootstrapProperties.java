package vn.edu.hsf302.lawrag.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for seeding an initial {@code ADMIN} account when none is enabled yet.
 */
@ConfigurationProperties(prefix = "app.security.bootstrap-admin")
public record AdminBootstrapProperties(
        boolean enabled,
        String username,
        String displayName,
        String password
) {
    @Override
    public String toString() {
        return "AdminBootstrapProperties[enabled=" + enabled
                + ", username=" + username
                + ", displayName=" + displayName
                + ", password=***]";
    }
}

