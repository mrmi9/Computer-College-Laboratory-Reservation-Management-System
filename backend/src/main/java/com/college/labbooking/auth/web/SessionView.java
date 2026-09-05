package com.college.labbooking.auth.web;

import java.time.Instant;

public record SessionView(
        String accessToken, Instant expiresAt, String csrfToken, UserView user) {}
