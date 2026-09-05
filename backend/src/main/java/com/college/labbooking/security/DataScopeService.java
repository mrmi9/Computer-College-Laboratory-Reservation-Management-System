package com.college.labbooking.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service("dataScope")
public class DataScopeService {
    private final JdbcTemplate jdbcTemplate;

    public DataScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean canManageLab(CurrentUser user, long labId) {
        if (user.hasRole("SYSTEM_ADMIN")) {
            return true;
        }
        Boolean managed = jdbcTemplate.queryForObject(
                "select exists(select 1 from lab_manager where lab_id = ? and user_id = ?)",
                Boolean.class,
                labId,
                user.id());
        return Boolean.TRUE.equals(managed);
    }
}
