package com.college.labbooking.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        String jwtSecret, int accessTokenMinutes, int refreshTokenDays, boolean cookieSecure) {
    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.security.jwt-secret must contain at least 32 UTF-8 bytes");
        }
        if (accessTokenMinutes < 1 || refreshTokenDays < 1) {
            throw new IllegalArgumentException("Token lifetimes must be positive");
        }
    }
}
