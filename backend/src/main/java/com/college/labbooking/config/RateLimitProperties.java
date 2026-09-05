package com.college.labbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, int maxPerMinute, int loginMaxPerMinute) {
    public RateLimitProperties {
        if (maxPerMinute < 1 || loginMaxPerMinute < 1) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
    }
}
