package com.college.labbooking.audit;

import com.college.labbooking.common.api.RequestIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(
            Long actorId,
            String actorUsername,
            String action,
            String targetType,
            String targetId,
            boolean success,
            Map<String, ?> detail) {
        jdbcTemplate.update(
                "insert into audit_log (actor_id, actor_username, action, target_type, target_id, request_id, result, detail) "
                        + "values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))",
                actorId,
                actorUsername,
                action,
                targetType,
                targetId,
                RequestIds.current(),
                success ? "SUCCESS" : "FAILURE",
                toJson(detail));
    }

    private String toJson(Map<String, ?> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize audit detail", exception);
        }
    }
}
