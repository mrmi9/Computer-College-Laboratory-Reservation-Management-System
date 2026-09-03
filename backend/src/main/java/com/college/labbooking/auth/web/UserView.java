package com.college.labbooking.auth.web;

import java.util.Set;

public record UserView(
        long id,
        String username,
        String realName,
        String userType,
        String department,
        String email,
        String phone,
        Set<String> roles,
        Set<String> permissions,
        boolean mustChangePassword) {}
