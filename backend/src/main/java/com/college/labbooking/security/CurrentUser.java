package com.college.labbooking.security;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUser(
        long id,
        String username,
        Set<String> roles,
        Set<String> permissions,
        boolean mustChangePassword) {

    public static CurrentUser from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("JWT authentication is required");
        }
        Number userId = jwt.getClaim("uid");
        return new CurrentUser(
                userId.longValue(),
                jwt.getSubject(),
                stringSet(jwt.getClaimAsStringList("roles")),
                stringSet(jwt.getClaimAsStringList("permissions")),
                Boolean.TRUE.equals(jwt.getClaim("must_change_password")));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    private static Set<String> stringSet(java.util.List<String> source) {
        return source == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(source));
    }
}
