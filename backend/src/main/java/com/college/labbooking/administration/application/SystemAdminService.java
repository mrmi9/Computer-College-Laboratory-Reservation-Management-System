package com.college.labbooking.administration.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemAdminService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public SystemAdminService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public List<SettingView> settings() {
        return jdbcTemplate.query(
                "select setting_key,setting_value::text,description,version,updated_by,updated_at "
                        + "from system_setting order by setting_key",
                (rs, row) -> new SettingView(rs.getString("setting_key"), json(rs.getString("setting_value")),
                        rs.getString("description"), rs.getLong("version"), nullableLong(rs, "updated_by"),
                        rs.getObject("updated_at", OffsetDateTime.class)));
    }

    @Transactional
    public SettingView updateSetting(
            String key, JsonNode value, String description, long expectedVersion, CurrentUser actor) {
        if (value == null || value.isContainerNode() || value.isNull() || value.toString().length() > 100) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "SETTING_VALUE_INVALID",
                    "系统参数值必须是长度不超过 100 的字符串、数字或布尔值");
        }
        validateSettingValue(key, value);
        int updated = jdbcTemplate.update(
                "update system_setting set setting_value=cast(? as jsonb),description=coalesce(?,description),"
                        + "version=version+1,updated_by=?,updated_at=now() where setting_key=? and version=?",
                value.toString(), blankToNull(description), actor.id(), key, expectedVersion);
        if (updated == 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from system_setting where setting_key=?", Integer.class, key);
            if (exists == null || exists == 0) {
                throw new AppException(HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND", "系统参数不存在");
            }
            throw new AppException(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "系统参数已被其他操作更新");
        }
        auditService.record(actor.id(), actor.username(), "SYSTEM_SETTING_UPDATED", "SYSTEM_SETTING", key, true,
                Map.of("version", expectedVersion + 1));
        return setting(key);
    }

    public PageView<AuditLogView> auditLogs(
            String action, String actorUsername, String targetType, OffsetDateTime from, OffsetDateTime to,
            int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "DATE_RANGE_INVALID", "开始时间不能晚于结束时间");
        }
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> parameters = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" and action=?");
            parameters.add(action.trim());
        }
        if (actorUsername != null && !actorUsername.isBlank()) {
            where.append(" and lower(actor_username) like ?");
            parameters.add("%" + actorUsername.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" and target_type=?");
            parameters.add(targetType.trim());
        }
        if (from != null) {
            where.append(" and created_at>=?");
            parameters.add(from);
        }
        if (to != null) {
            where.append(" and created_at<=?");
            parameters.add(to);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from audit_log" + where, Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(size);
        queryParameters.add((long) page * size);
        List<AuditLogView> items = jdbcTemplate.query(
                "select id,actor_id,actor_username,action,target_type,target_id,request_id,result,detail::text,"
                        + "ip_address::text,created_at from audit_log" + where + " order by created_at desc,id desc limit ? offset ?",
                (rs, row) -> new AuditLogView(rs.getLong("id"), nullableLong(rs, "actor_id"),
                        rs.getString("actor_username"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("request_id"), rs.getString("result"),
                        json(rs.getString("detail")), rs.getString("ip_address"),
                        rs.getObject("created_at", OffsetDateTime.class)), queryParameters.toArray());
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    private SettingView setting(String key) {
        List<SettingView> rows = jdbcTemplate.query(
                "select setting_key,setting_value::text,description,version,updated_by,updated_at "
                        + "from system_setting where setting_key=?",
                (rs, row) -> new SettingView(rs.getString("setting_key"), json(rs.getString("setting_value")),
                        rs.getString("description"), rs.getLong("version"), nullableLong(rs, "updated_by"),
                        rs.getObject("updated_at", OffsetDateTime.class)), key);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND", "系统参数不存在");
        return rows.getFirst();
    }

    private void validateSettingValue(String key, JsonNode value) {
        switch (key) {
            case "attendance.check-in-before-minutes", "attendance.check-in-grace-minutes" ->
                requireInteger(value, 0, 120, "签到窗口分钟数");
            case "attendance.auto-complete" -> {
                if (!value.isBoolean()) {
                    throw invalidSetting("自动完成参数必须是布尔值");
                }
            }
            case "violation.no-show-threshold" -> requireInteger(value, 1, 100, "爽约阈值");
            case "violation.freeze-days" -> requireInteger(value, 1, 3650, "冻结天数");
            case "export.max-rows" -> requireInteger(value, 1, 10000, "导出行数上限");
            default -> throw new AppException(HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND", "系统参数不存在");
        }
    }

    private void requireInteger(JsonNode value, int minimum, int maximum, String label) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalidSetting(label + "必须是 " + minimum + " 到 " + maximum + " 之间的整数");
        }
    }

    private AppException invalidSetting(String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "SETTING_VALUE_INVALID", message);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored JSON is invalid", exception);
        }
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SettingView(String key, JsonNode value, String description, long version, Long updatedBy,
            OffsetDateTime updatedAt) {}

    public record AuditLogView(long id, Long actorId, String actorUsername, String action, String targetType,
            String targetId, String requestId, String result, JsonNode detail, String ipAddress,
            OffsetDateTime createdAt) {}
}
