package com.college.labbooking.identity.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAdminService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Set<String> USER_TYPES = Set.of("STUDENT", "TEACHER", "STAFF");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED", "LOCKED");
    private static final List<String> IMPORT_HEADER = List.of(
            "username", "realName", "userType", "department", "email", "phone", "roles", "initialPassword");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public IdentityAdminService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public PageView<UserAdminView> users(String keyword, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(u.username) like ? or lower(u.real_name) like ?)");
            String value = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            parameters.add(value);
            parameters.add(value);
        }
        if (status != null && !status.isBlank()) {
            requireStatus(status);
            where.append(" and u.status=?");
            parameters.add(status);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u" + where, Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(size);
        queryParameters.add((long) page * size);
        List<UserAdminView> items = jdbcTemplate.query(
                "select u.id,u.username,u.real_name,u.user_type,u.department,u.email,u.phone,u.status,"
                        + "u.must_change_password,u.booking_frozen_until,u.no_show_count,u.version,u.created_at,"
                        + "coalesce(array_agg(r.code order by r.code) filter (where r.code is not null),'{}') roles "
                        + "from sys_user u left join sys_user_role ur on ur.user_id=u.id "
                        + "left join sys_role r on r.id=ur.role_id" + where + " group by u.id order by u.id limit ? offset ?",
                (rs, row) -> mapUser(rs), queryParameters.toArray());
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    public UserAdminView user(long id) {
        List<UserAdminView> users = jdbcTemplate.query(
                "select u.id,u.username,u.real_name,u.user_type,u.department,u.email,u.phone,u.status,"
                        + "u.must_change_password,u.booking_frozen_until,u.no_show_count,u.version,u.created_at,"
                        + "coalesce(array_agg(r.code order by r.code) filter (where r.code is not null),'{}') roles "
                        + "from sys_user u left join sys_user_role ur on ur.user_id=u.id "
                        + "left join sys_role r on r.id=ur.role_id where u.id=? group by u.id",
                (rs, row) -> mapUser(rs), id);
        if (users.isEmpty()) throw notFound("USER_NOT_FOUND", "用户不存在");
        return users.getFirst();
    }

    @Transactional
    public UserAdminView createUser(UserMutation mutation, Set<String> roles, String initialPassword, CurrentUser actor) {
        long id = insertUser(mutation, roles, initialPassword);
        auditService.record(actor.id(), actor.username(), "USER_CREATED", "USER", Long.toString(id), true,
                Map.of("username", mutation.username().trim(), "roles", normalizeCodes(roles)));
        return user(id);
    }

    @Transactional
    public UserAdminView updateUser(long id, UserMutation mutation, long expectedVersion, CurrentUser actor) {
        UserAdminView existing = user(id);
        if (actor.id() == id && !"ACTIVE".equals(mutation.status())) {
            throw business("SELF_DISABLE_NOT_ALLOWED", "不能禁用或锁定当前登录账号");
        }
        if (existing.roles().contains("SYSTEM_ADMIN") && !"ACTIVE".equals(mutation.status())) {
            requireAnotherActiveSystemAdministrator(id);
        }
        validateMutation(mutation);
        int revoke = existing.status().equals(mutation.status()) ? 0 : 1;
        int updated = jdbcTemplate.update(
                "update sys_user set username=?,real_name=?,user_type=?,department=?,email=?,phone=?,status=?,"
                        + "failed_login_count=case when ?='ACTIVE' then 0 else failed_login_count end,"
                        + "locked_until=case when ?='ACTIVE' then null else locked_until end,"
                        + "session_version=session_version+?,version=version+1,updated_at=now() where id=? and version=?",
                mutation.username().trim(), mutation.realName().trim(), mutation.userType(), blankToNull(mutation.department()),
                blankToNull(mutation.email()), blankToNull(mutation.phone()), mutation.status(), mutation.status(),
                mutation.status(), revoke, id, expectedVersion);
        if (updated == 0) throw versionConflict();
        if (revoke == 1) revokeRefreshSessions(id, "USER_STATUS_CHANGED");
        auditService.record(actor.id(), actor.username(), "USER_UPDATED", "USER", Long.toString(id), true,
                Map.of("status", mutation.status(), "version", expectedVersion + 1));
        return user(id);
    }

    @Transactional
    public UserAdminView replaceUserRoles(long id, Set<String> roleCodes, long expectedVersion, CurrentUser actor) {
        UserAdminView existing = user(id);
        Set<String> normalized = normalizeCodes(roleCodes);
        validateRoleAssignment(existing.userType(), normalized);
        if (actor.id() == id && !normalized.contains("SYSTEM_ADMIN")) {
            throw business("SELF_ROLE_REMOVAL_NOT_ALLOWED", "不能移除当前登录账号的系统管理员角色");
        }
        if (existing.roles().contains("SYSTEM_ADMIN") && !normalized.contains("SYSTEM_ADMIN")) {
            requireAnotherActiveSystemAdministrator(id);
        }
        Map<String, Long> roleIds = resolveRoles(normalized);
        int updated = jdbcTemplate.update(
                "update sys_user set session_version=session_version+1,version=version+1,updated_at=now() "
                        + "where id=? and version=?",
                id, expectedVersion);
        if (updated == 0) throw versionConflict();
        jdbcTemplate.update("delete from sys_user_role where user_id=?", id);
        roleIds.values().forEach(roleId -> jdbcTemplate.update(
                "insert into sys_user_role (user_id,role_id) values (?,?)", id, roleId));
        revokeRefreshSessions(id, "USER_ROLES_CHANGED");
        auditService.record(actor.id(), actor.username(), "USER_ROLES_CHANGED", "USER", Long.toString(id), true,
                Map.of("before", existing.roles(), "after", normalized));
        return user(id);
    }

    public List<RoleAdminView> roles() {
        return jdbcTemplate.query(
                "select id,code,name,description,built_in,version from sys_role order by built_in desc,code",
                (rs, row) -> mapRole(rs));
    }

    @Transactional
    public RoleAdminView createRole(
            String code, String name, String description, Set<String> permissions, CurrentUser actor) {
        String normalizedCode = normalizeRoleCode(code);
        Set<String> normalizedPermissions = normalizePermissions(permissions);
        Map<String, Long> permissionIds = resolvePermissions(normalizedPermissions);
        Long id = jdbcTemplate.queryForObject(
                "insert into sys_role (code,name,description,built_in) values (?,?,?,false) returning id",
                Long.class, normalizedCode, name.trim(), blankToNull(description));
        permissionIds.values().forEach(permissionId -> jdbcTemplate.update(
                "insert into sys_role_permission (role_id,permission_id) values (?,?)", id, permissionId));
        auditService.record(actor.id(), actor.username(), "ROLE_CREATED", "ROLE", String.valueOf(id), true,
                Map.of("code", normalizedCode, "permissions", normalizedPermissions));
        return role(id);
    }

    @Transactional
    public RoleAdminView updateRole(
            long id, String name, String description, Set<String> permissions, long expectedVersion, CurrentUser actor) {
        RoleAdminView existing = role(id);
        Set<String> normalizedPermissions = normalizePermissions(permissions);
        Map<String, Long> permissionIds = resolvePermissions(normalizedPermissions);
        if ("SYSTEM_ADMIN".equals(existing.code())) {
            Set<String> all = resolvePermissions(null).keySet();
            if (!normalizedPermissions.equals(all)) {
                throw business("BUILT_IN_ROLE_GUARD", "系统管理员角色必须保留全部权限");
            }
        }
        int updated = jdbcTemplate.update(
                "update sys_role set name=?,description=?,version=version+1 where id=? and version=?",
                name.trim(), blankToNull(description), id, expectedVersion);
        if (updated == 0) throw versionConflict();
        jdbcTemplate.update("delete from sys_role_permission where role_id=?", id);
        permissionIds.values().forEach(permissionId -> jdbcTemplate.update(
                "insert into sys_role_permission (role_id,permission_id) values (?,?)", id, permissionId));
        jdbcTemplate.update("update sys_user set session_version=session_version+1,version=version+1,updated_at=now() "
                + "where id in (select user_id from sys_user_role where role_id=?)", id);
        jdbcTemplate.update("update refresh_session set revoked_at=coalesce(revoked_at,now()),revoke_reason=coalesce(revoke_reason,'ROLE_CHANGED') "
                + "where user_id in (select user_id from sys_user_role where role_id=?) and revoked_at is null", id);
        auditService.record(actor.id(), actor.username(), "ROLE_UPDATED", "ROLE", Long.toString(id), true,
                Map.of("code", existing.code(), "permissions", normalizedPermissions));
        return role(id);
    }

    public RoleAdminView role(long id) {
        List<RoleAdminView> rows = jdbcTemplate.query(
                "select id,code,name,description,built_in,version from sys_role where id=?",
                (rs, row) -> mapRole(rs), id);
        if (rows.isEmpty()) throw notFound("ROLE_NOT_FOUND", "角色不存在");
        return rows.getFirst();
    }

    public List<PermissionView> permissions() {
        return jdbcTemplate.query("select id,code,name from sys_permission order by code",
                (rs, row) -> new PermissionView(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
    }

    @Transactional
    public ImportTaskView importUsers(String fileName, byte[] content, CurrentUser actor) {
        if (content.length == 0 || content.length > 1024 * 1024) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_SIZE_INVALID", "导入文件必须为 1MB 以内的非空 CSV");
        }
        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update("insert into user_import_task (id,created_by,file_name,status) values (?,?,?,'PROCESSING')",
                taskId, actor.id(), safeFileName(fileName));
        List<String> errors = new ArrayList<>();
        int total = 0;
        int succeeded = 0;
        try {
            String csv = decodeUtf8(content);
            List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty() || !parseCsvLine(stripBom(lines.getFirst())).equals(IMPORT_HEADER)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "IMPORT_HEADER_INVALID",
                        "CSV 表头必须为 username,realName,userType,department,email,phone,roles,initialPassword");
            }
            if (lines.size() > 2001) {
                throw new AppException(HttpStatus.BAD_REQUEST, "IMPORT_ROW_LIMIT_EXCEEDED", "单次最多导入 2000 个用户");
            }
            for (int index = 1; index < lines.size(); index++) {
                total++;
                List<String> fields = parseCsvLine(lines.get(index));
                try {
                    if (fields.size() != IMPORT_HEADER.size()) {
                        throw business("IMPORT_ROW_INVALID", "列数不正确");
                    }
                    Set<String> roles = new LinkedHashSet<>(Arrays.asList(fields.get(6).split("\\|")));
                    UserMutation mutation = new UserMutation(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                            fields.get(4), fields.get(5), "ACTIVE");
                    insertUser(mutation, roles, fields.get(7));
                    succeeded++;
                } catch (AppException exception) {
                    if (errors.size() < 10) errors.add("第 " + (index + 1) + " 行：" + exception.getMessage());
                }
            }
            jdbcTemplate.update("update user_import_task set status='COMPLETED',total_rows=?,succeeded_rows=?,"
                            + "failed_rows=?,error_summary=?,completed_at=now() where id=?",
                    total, succeeded, total - succeeded, errors.isEmpty() ? null : String.join("；", errors), taskId);
            auditService.record(actor.id(), actor.username(), "USERS_IMPORTED", "USER_IMPORT_TASK", taskId.toString(), true,
                    Map.of("total", total, "succeeded", succeeded, "failed", total - succeeded));
        } catch (AppException exception) {
            jdbcTemplate.update("update user_import_task set status='FAILED',total_rows=?,succeeded_rows=?,failed_rows=?,"
                            + "error_summary=?,completed_at=now() where id=?",
                    total, succeeded, total - succeeded, limit(exception.getMessage(), 2000), taskId);
            auditService.record(actor.id(), actor.username(), "USERS_IMPORTED", "USER_IMPORT_TASK", taskId.toString(), false,
                    Map.of("reason", exception.code()));
            throw exception;
        }
        return importTask(taskId);
    }

    public ImportTaskView importTask(UUID id) {
        List<ImportTaskView> tasks = jdbcTemplate.query(
                "select id,created_by,file_name,status,total_rows,succeeded_rows,failed_rows,error_summary,created_at,completed_at "
                        + "from user_import_task where id=?",
                (rs, row) -> new ImportTaskView(rs.getObject("id", UUID.class), rs.getLong("created_by"),
                        rs.getString("file_name"), rs.getString("status"), rs.getInt("total_rows"),
                        rs.getInt("succeeded_rows"), rs.getInt("failed_rows"), rs.getString("error_summary"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class)), id);
        if (tasks.isEmpty()) throw notFound("IMPORT_TASK_NOT_FOUND", "导入任务不存在");
        return tasks.getFirst();
    }

    private long insertUser(UserMutation mutation, Set<String> roles, String initialPassword) {
        validateMutation(mutation);
        if (initialPassword == null || initialPassword.length() < 12 || initialPassword.length() > 128) {
            throw business("INITIAL_PASSWORD_INVALID", "初始密码长度必须为 12 到 128 个字符");
        }
        Set<String> normalizedRoles = normalizeCodes(roles);
        validateRoleAssignment(mutation.userType(), normalizedRoles);
        Map<String, Long> roleIds = resolveRoles(normalizedRoles);
        Integer exists = jdbcTemplate.queryForObject("select count(*) from sys_user where username=?", Integer.class,
                mutation.username().trim());
        if (exists != null && exists > 0) throw new AppException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        Long id = jdbcTemplate.queryForObject(
                "insert into sys_user (username,password_hash,real_name,user_type,department,email,phone,status,must_change_password) "
                        + "values (?,?,?,?,?,?,?,?,true) returning id",
                Long.class, mutation.username().trim(), passwordEncoder.encode(initialPassword), mutation.realName().trim(),
                mutation.userType(), blankToNull(mutation.department()), blankToNull(mutation.email()),
                blankToNull(mutation.phone()), mutation.status());
        roleIds.values().forEach(roleId -> jdbcTemplate.update(
                "insert into sys_user_role (user_id,role_id) values (?,?)", id, roleId));
        return id;
    }

    private void validateMutation(UserMutation mutation) {
        if (mutation.username() == null || !USERNAME.matcher(mutation.username().trim()).matches()) {
            throw business("USERNAME_INVALID", "用户名只能包含字母、数字、点、下划线和连字符，长度为 3 到 64");
        }
        if (mutation.realName() == null || mutation.realName().isBlank() || mutation.realName().length() > 64) {
            throw business("REAL_NAME_INVALID", "姓名不能为空且最多 64 个字符");
        }
        if (!USER_TYPES.contains(mutation.userType())) throw business("USER_TYPE_INVALID", "用户类型无效");
        requireStatus(mutation.status());
        requireLength(mutation.department(), 100, "部门");
        requireLength(mutation.email(), 128, "邮箱");
        requireLength(mutation.phone(), 32, "手机号");
    }

    private void validateRoleAssignment(String userType, Set<String> roles) {
        if (roles.isEmpty()) throw business("ROLE_REQUIRED", "用户至少需要一个角色");
        if ("STUDENT".equals(userType) && !roles.contains("STUDENT")) {
            throw business("BUSINESS_ROLE_REQUIRED", "学生用户必须保留 STUDENT 业务身份");
        }
        if ("TEACHER".equals(userType) && !roles.contains("TEACHER")) {
            throw business("BUSINESS_ROLE_REQUIRED", "教师用户必须保留 TEACHER 业务身份");
        }
    }

    private Map<String, Long> resolveRoles(Set<String> codes) {
        if (codes.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        Map<String, Long> found = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("select id,code from sys_role where code in (" + placeholders + ")",
                rs -> {
                    found.put(rs.getString("code"), rs.getLong("id"));
                }, codes.toArray());
        if (!found.keySet().equals(codes)) throw business("ROLE_NOT_FOUND", "包含不存在的角色编码");
        return found;
    }

    private Map<String, Long> resolvePermissions(Set<String> codes) {
        Map<String, Long> found = new java.util.LinkedHashMap<>();
        if (codes == null) {
            jdbcTemplate.query("select id,code from sys_permission", rs -> {
                found.put(rs.getString("code"), rs.getLong("id"));
            });
            return found;
        }
        if (codes.isEmpty()) return found;
        String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        jdbcTemplate.query("select id,code from sys_permission where code in (" + placeholders + ")",
                rs -> {
                    found.put(rs.getString("code"), rs.getLong("id"));
                }, codes.toArray());
        if (!found.keySet().equals(codes)) throw business("PERMISSION_NOT_FOUND", "包含不存在的权限编码");
        return found;
    }

    private RoleAdminView mapRole(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<String> permissions = jdbcTemplate.queryForList(
                "select p.code from sys_role_permission rp join sys_permission p on p.id=rp.permission_id "
                        + "where rp.role_id=? order by p.code",
                String.class, id);
        return new RoleAdminView(id, rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("built_in"), rs.getLong("version"), Set.copyOf(permissions));
    }

    private UserAdminView mapUser(ResultSet rs) throws SQLException {
        Array sqlRoles = rs.getArray("roles");
        String[] roles = sqlRoles == null ? new String[0] : (String[]) sqlRoles.getArray();
        return new UserAdminView(rs.getLong("id"), rs.getString("username"), rs.getString("real_name"),
                rs.getString("user_type"), rs.getString("department"), rs.getString("email"), rs.getString("phone"),
                rs.getString("status"), rs.getBoolean("must_change_password"),
                rs.getObject("booking_frozen_until", OffsetDateTime.class), rs.getInt("no_show_count"),
                rs.getLong("version"), Set.copyOf(Arrays.asList(roles)),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private void requireAnotherActiveSystemAdministrator(long excludedId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u join sys_user_role ur on ur.user_id=u.id "
                        + "join sys_role r on r.id=ur.role_id where r.code='SYSTEM_ADMIN' and u.status='ACTIVE' and u.id<>?",
                Integer.class, excludedId);
        if (count == null || count == 0) throw business("LAST_SYSTEM_ADMIN_GUARD", "必须至少保留一个可用的系统管理员账号");
    }

    private void revokeRefreshSessions(long userId, String reason) {
        jdbcTemplate.update("update refresh_session set revoked_at=coalesce(revoked_at,now()),"
                + "revoke_reason=coalesce(revoke_reason,?) where user_id=? and revoked_at is null", reason, userId);
    }

    private void requireStatus(String status) {
        if (!USER_STATUSES.contains(status)) throw business("USER_STATUS_INVALID", "用户状态无效");
    }

    private Set<String> normalizeCodes(Set<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> {
            if (value != null && !value.isBlank()) normalized.add(normalizeRoleCode(value));
        });
        return Set.copyOf(normalized);
    }

    private Set<String> normalizePermissions(Set<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> {
            if (value != null && !value.isBlank()) normalized.add(value.trim().toLowerCase(Locale.ROOT));
        });
        return Set.copyOf(normalized);
    }

    private String normalizeRoleCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_CODE.matcher(code).matches()) throw business("ROLE_CODE_INVALID", "角色编码格式无效");
        return code;
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMPORT_ENCODING_INVALID", "CSV 必须使用 UTF-8 编码");
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) throw business("IMPORT_CSV_INVALID", "CSV 引号未闭合");
        fields.add(field.toString().trim());
        return fields;
    }

    private String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "users.csv";
        String clean = fileName.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1);
        return limit(clean, 255);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private void requireLength(String value, int max, String label) {
        if (value != null && value.length() > max) throw business("FIELD_TOO_LONG", label + "长度超过限制");
    }

    private AppException versionConflict() {
        return new AppException(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "资源已被其他操作更新，请刷新后重试");
    }

    private AppException notFound(String code, String message) {
        return new AppException(HttpStatus.NOT_FOUND, code, message);
    }

    private AppException business(String code, String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public record UserMutation(String username, String realName, String userType, String department, String email,
            String phone, String status) {}

    public record UserAdminView(long id, String username, String realName, String userType, String department,
            String email, String phone, String status, boolean mustChangePassword, OffsetDateTime bookingFrozenUntil,
            int noShowCount, long version, Set<String> roles, OffsetDateTime createdAt) {}

    public record RoleAdminView(long id, String code, String name, String description, boolean builtIn, long version,
            Set<String> permissions) {}

    public record PermissionView(long id, String code, String name) {}

    public record ImportTaskView(UUID id, long createdBy, String fileName, String status, int totalRows,
            int succeededRows, int failedRows, String errorSummary, OffsetDateTime createdAt, OffsetDateTime completedAt) {}
}
