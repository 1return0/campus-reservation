package com.campusreserve.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campus-reserve.bootstrap.admin")
public record BootstrapAdminProperties(
        String username,
        String password,
        String displayName
) {

    public boolean isConfigured() {
        return isNonBlank(username) || isNonBlank(password) || isNonBlank(displayName);
    }

    public boolean isComplete() {
        return isNonBlank(username) && isNonBlank(password) && isNonBlank(displayName);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
