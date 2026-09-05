package com.college.labbooking.catalog.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.catalog.web.CatalogController.BlackoutRequest;
import com.college.labbooking.catalog.web.CatalogController.EquipmentRequest;
import com.college.labbooking.catalog.web.CatalogController.LabRequest;
import com.college.labbooking.catalog.web.CatalogController.OpenRuleRequest;
import com.college.labbooking.catalog.web.CatalogController.PeriodRequest;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.security.DataScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScope;
    private final AuditService auditService;

    public CatalogService(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DataScopeService dataScope, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    public List<PeriodView> periods() {
        return jdbcTemplate.query(
                "select period_no, name, start_time, end_time, enabled, version from course_period order by period_no",
                (rs, row) -> new PeriodView(
                        rs.getInt("period_no"),
                        rs.getString("name"),
                        rs.getTime("start_time").toLocalTime(),
                        rs.getTime("end_time").toLocalTime(),
                        rs.getBoolean("enabled"),
                        rs.getLong("version")));
    }

    @Transactional
    public PeriodView updatePeriod(int periodNo, PeriodRequest request, CurrentUser user) {
        int updated = jdbcTemplate.update(
                "update course_period set name=?, start_time=?, end_time=?, enabled=?, version=version+1, "
                        + "updated_at=now() where period_no=? and version=?",
                request.name().trim(),
                Time.valueOf(request.startTime()),
                Time.valueOf(request.endTime()),
                request.enabled(),
                periodNo,
                request.version());
        requireUpdated(updated, "COURSE_PERIOD_NOT_FOUND", "课次不存在");
        auditService.record(user.id(), user.username(), "COURSE_PERIOD_UPDATED", "COURSE_PERIOD", String.valueOf(periodNo), true,
                Map.of("version", request.version()));
        return periods().stream().filter(period -> period.periodNo() == periodNo).findFirst().orElseThrow();
    }

    public PageView<LabView> labs(String keyword, String status, int page, int size) {
        String normalized = keyword == null ? "" : keyword.trim();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        String where = " where (? = '' or lower(l.name) like lower(?) or lower(l.code) like lower(?) "
                + "or lower(l.building) like lower(?)) and (? = '' or l.status = ?)";
        String like = "%" + normalized + "%";
        Object[] params = {normalized, like, like, like, normalizedStatus, normalizedStatus};
        Long total = jdbcTemplate.queryForObject("select count(*) from lab l" + where, Long.class, params);
        Object[] pageParams = {normalized, like, like, like, normalizedStatus, normalizedStatus, size, (long) page * size};
        List<LabView> items = jdbcTemplate.query(
                "select l.*,u.real_name responsible_user_name from lab l left join sys_user u on u.id=l.responsible_user_id"
                        + where + " order by l.code limit ? offset ?",
                this::labView,
                pageParams);
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    public LabView lab(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select l.*,u.real_name responsible_user_name from lab l left join sys_user u on u.id=l.responsible_user_id where l.id=?",
                    this::labView,
                    id);
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("LAB_NOT_FOUND", "实验室不存在");
        }
    }

    @Transactional
    public LabView createLab(LabRequest request, CurrentUser user) {
        if (!user.hasRole("SYSTEM_ADMIN")) {
            throw new AppException(HttpStatus.FORBIDDEN, "LAB_SCOPE_DENIED", "仅系统管理员可以创建实验室");
        }
        long responsibleUserId = requireResponsibleUser(request.responsibleUserId());
        Long id = jdbcTemplate.queryForObject(
                "insert into lab (code,name,building,room_no,capacity,lab_type,description,image_url,tags,status,"
                        + "student_approval_mode,teacher_approval_mode,allow_student_booking,max_periods_per_user_day,"
                        + "advance_days,cancel_before_minutes,require_check_in,responsible_user_id) values (?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?,?) returning id",
                Long.class,
                request.code().trim(), request.name().trim(), request.building().trim(), request.roomNo().trim(),
                request.capacity(), request.labType().trim(), request.description(), request.imageUrl(), json(request.tags()),
                request.status(), request.studentApprovalMode(), request.teacherApprovalMode(), request.allowStudentBooking(),
                request.maxPeriodsPerUserDay(), request.advanceDays(), request.cancelBeforeMinutes(), request.requireCheckIn(),
                responsibleUserId);
        syncResponsibleManager(id, responsibleUserId);
        auditService.record(user.id(), user.username(), "LAB_CREATED", "LAB", String.valueOf(id), true, Map.of("code", request.code()));
        return lab(id);
    }

    @Transactional
    public LabView updateLab(long id, LabRequest request, CurrentUser user) {
        requireManage(user, id);
        LabView existing = lab(id);
        Long responsibleUserId = request.responsibleUserId() == null
                ? existing.responsibleUserId()
                : requireResponsibleUser(request.responsibleUserId());
        if (!user.hasRole("SYSTEM_ADMIN") && !Objects.equals(existing.responsibleUserId(), responsibleUserId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "LAB_RESPONSIBLE_CHANGE_DENIED", "仅系统管理员可以变更实验室负责人");
        }
        int updated = jdbcTemplate.update(
                "update lab set code=?,name=?,building=?,room_no=?,capacity=?,lab_type=?,description=?,image_url=?,"
                        + "tags=cast(? as jsonb),status=?,student_approval_mode=?,teacher_approval_mode=?,allow_student_booking=?,"
                        + "max_periods_per_user_day=?,advance_days=?,cancel_before_minutes=?,require_check_in=?,responsible_user_id=?,version=version+1,updated_at=now() "
                        + "where id=? and version=?",
                request.code().trim(), request.name().trim(), request.building().trim(), request.roomNo().trim(),
                request.capacity(), request.labType().trim(), request.description(), request.imageUrl(), json(request.tags()),
                request.status(), request.studentApprovalMode(), request.teacherApprovalMode(), request.allowStudentBooking(),
                request.maxPeriodsPerUserDay(), request.advanceDays(), request.cancelBeforeMinutes(), request.requireCheckIn(),
                responsibleUserId, id, request.version());
        requireUpdated(updated, "LAB_NOT_FOUND", "实验室不存在");
        syncResponsibleManager(id, responsibleUserId);
        auditService.record(user.id(), user.username(), "LAB_UPDATED", "LAB", String.valueOf(id), true, Map.of("version", request.version()));
        return lab(id);
    }

    public List<CalendarSlotView> calendar(long labId, LocalDate from, LocalDate to) {
        lab(labId);
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(6) : to;
        long days = ChronoUnit.DAYS.between(start, end);
        if (days < 0 || days > 30) {
            throw new AppException(HttpStatus.BAD_REQUEST, "DATE_RANGE_INVALID", "日历范围必须为连续的 1 至 31 天");
        }
        return jdbcTemplate.query(
                """
                select d.booking_date,p.period_no,p.name period_name,p.start_time,p.end_time,
                       case when b.id is not null then 'BLACKOUT'
                            when r.id is not null then 'RESERVED'
                            when l.status <> 'ACTIVE' or not p.enabled then 'CLOSED'
                            when not exists(select 1 from lab_open_rule o where o.lab_id=l.id
                                 and o.day_of_week=extract(isodow from d.booking_date)
                                 and o.period_no=p.period_no
                                 and (o.valid_from is null or o.valid_from<=d.booking_date)
                                 and (o.valid_to is null or o.valid_to>=d.booking_date)) then 'CLOSED'
                            else 'AVAILABLE' end slot_status,
                       b.reason
                  from (select generate_series(cast(? as date),cast(? as date),interval '1 day')::date booking_date) d
                  cross join course_period p
                  cross join lab l
                  left join lab_blackout b on b.lab_id=l.id and b.booking_date=d.booking_date and b.period_no=p.period_no
                  left join lateral (select rr.id from reservation rr where rr.lab_id=l.id
                       and rr.booking_date=d.booking_date and rr.period_no=p.period_no
                       and rr.status in ('APPROVED','IN_USE','COMPLETED') limit 1) r on true
                 where l.id=?
                 order by d.booking_date,p.period_no
                """,
                (rs, row) -> new CalendarSlotView(
                        rs.getDate("booking_date").toLocalDate(),
                        rs.getInt("period_no"),
                        rs.getString("period_name"),
                        rs.getTime("start_time").toLocalTime(),
                        rs.getTime("end_time").toLocalTime(),
                        rs.getString("slot_status"),
                        rs.getString("reason")),
                start,
                end,
                labId);
    }

    public List<OpenRuleView> openRules(long labId) {
        lab(labId);
        return jdbcTemplate.query(
                "select id,lab_id,day_of_week,period_no,valid_from,valid_to from lab_open_rule where lab_id=? "
                        + "order by day_of_week,period_no,valid_from nulls first",
                (rs, row) -> new OpenRuleView(rs.getLong("id"), rs.getLong("lab_id"), rs.getInt("day_of_week"),
                        rs.getInt("period_no"), localDate(rs, "valid_from"), localDate(rs, "valid_to")), labId);
    }

    @Transactional
    public List<OpenRuleView> replaceOpenRules(long labId, List<OpenRuleRequest> rules, CurrentUser user) {
        requireManage(user, labId);
        jdbcTemplate.update("delete from lab_open_rule where lab_id=?", labId);
        for (OpenRuleRequest rule : rules) {
            jdbcTemplate.update(
                    "insert into lab_open_rule (lab_id,day_of_week,period_no,valid_from,valid_to) values (?,?,?,?,?)",
                    labId, rule.dayOfWeek(), rule.periodNo(), rule.validFrom(), rule.validTo());
        }
        auditService.record(user.id(), user.username(), "LAB_OPEN_RULES_REPLACED", "LAB", String.valueOf(labId), true,
                Map.of("ruleCount", rules.size()));
        return openRules(labId);
    }

    public List<BlackoutView> blackouts(long labId, LocalDate from, LocalDate to) {
        lab(labId);
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusMonths(3) : to;
        return jdbcTemplate.query(
                "select b.id,b.lab_id,b.booking_date,b.period_no,b.reason,b.created_by,b.created_at,u.real_name created_by_name "
                        + "from lab_blackout b join sys_user u on u.id=b.created_by where b.lab_id=? and b.booking_date between ? and ? "
                        + "order by b.booking_date,b.period_no",
                (rs, row) -> new BlackoutView(rs.getLong("id"), rs.getLong("lab_id"), rs.getDate("booking_date").toLocalDate(),
                        rs.getInt("period_no"), rs.getString("reason"), rs.getLong("created_by"),
                        rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class)),
                labId, start, end);
    }

    @Transactional
    public BlackoutView createBlackout(long labId, BlackoutRequest request, CurrentUser user) {
        requireManage(user, labId);
        Long id = jdbcTemplate.queryForObject(
                "insert into lab_blackout (lab_id,booking_date,period_no,reason,created_by) values (?,?,?,?,?) returning id",
                Long.class, labId, request.bookingDate(), request.periodNo(), request.reason().trim(), user.id());
        auditService.record(user.id(), user.username(), "LAB_BLACKOUT_CREATED", "LAB_BLACKOUT", String.valueOf(id), true,
                Map.of("labId", labId, "bookingDate", request.bookingDate().toString(), "periodNo", request.periodNo()));
        return blackouts(labId, request.bookingDate(), request.bookingDate()).stream()
                .filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteBlackout(long labId, long blackoutId, CurrentUser user) {
        requireManage(user, labId);
        int deleted = jdbcTemplate.update("delete from lab_blackout where id=? and lab_id=?", blackoutId, labId);
        if (deleted == 0) throw notFound("BLACKOUT_NOT_FOUND", "停用课次不存在");
        auditService.record(user.id(), user.username(), "LAB_BLACKOUT_DELETED", "LAB_BLACKOUT", String.valueOf(blackoutId), true,
                Map.of("labId", labId));
    }

    public PageView<EquipmentView> equipment(Long labId, String status, int page, int size) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        String where = " where (?='' or e.status=?)";
        List<Object> params = new java.util.ArrayList<>(List.of(normalizedStatus, normalizedStatus));
        if (labId != null) {
            where += " and e.lab_id=?";
            params.add(labId);
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from equipment e" + where, Long.class, params.toArray());
        params.add(size);
        params.add((long) page * size);
        List<EquipmentView> items = jdbcTemplate.query(
                "select e.*,l.name lab_name from equipment e join lab l on l.id=e.lab_id" + where
                        + " order by e.asset_code limit ? offset ?", this::equipmentView, params.toArray());
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public EquipmentView createEquipment(EquipmentRequest request, CurrentUser user) {
        requireManage(user, request.labId());
        Long id = jdbcTemplate.queryForObject(
                "insert into equipment (lab_id,asset_code,category,name,model,total_quantity,required_qualification,status) "
                        + "values (?,?,?,?,?,?,?,?) returning id",
                Long.class, request.labId(), request.assetCode().trim(), request.category().trim(), request.name().trim(),
                request.model(), request.totalQuantity(), request.requiredQualification(), request.status());
        auditService.record(user.id(), user.username(), "EQUIPMENT_CREATED", "EQUIPMENT", String.valueOf(id), true,
                Map.of("labId", request.labId(), "assetCode", request.assetCode()));
        return equipmentById(id);
    }

    @Transactional
    public EquipmentView updateEquipment(long id, EquipmentRequest request, CurrentUser user) {
        EquipmentView existing = equipmentById(id);
        requireManage(user, existing.labId());
        requireManage(user, request.labId());
        int updated = jdbcTemplate.update(
                "update equipment set lab_id=?,asset_code=?,category=?,name=?,model=?,total_quantity=?,required_qualification=?,"
                        + "status=?,version=version+1,updated_at=now() where id=? and version=?",
                request.labId(), request.assetCode().trim(), request.category().trim(), request.name().trim(), request.model(),
                request.totalQuantity(), request.requiredQualification(), request.status(), id, request.version());
        requireUpdated(updated, "EQUIPMENT_NOT_FOUND", "设备不存在");
        auditService.record(user.id(), user.username(), "EQUIPMENT_UPDATED", "EQUIPMENT", String.valueOf(id), true,
                Map.of("version", request.version()));
        return equipmentById(id);
    }

    private EquipmentView equipmentById(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select e.*,l.name lab_name from equipment e join lab l on l.id=e.lab_id where e.id=?",
                    this::equipmentView, id);
        } catch (EmptyResultDataAccessException exception) {
            throw notFound("EQUIPMENT_NOT_FOUND", "设备不存在");
        }
    }

    private LabView labView(ResultSet rs, int row) throws SQLException {
        return new LabView(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("building"),
                rs.getString("room_no"), rs.getInt("capacity"), rs.getString("lab_type"), rs.getString("description"),
                rs.getString("image_url"), stringList(rs.getString("tags")), rs.getString("status"),
                rs.getString("student_approval_mode"), rs.getString("teacher_approval_mode"),
                rs.getBoolean("allow_student_booking"), rs.getInt("max_periods_per_user_day"),
                rs.getInt("advance_days"), rs.getInt("cancel_before_minutes"), rs.getBoolean("require_check_in"),
                nullableLong(rs, "responsible_user_id"), rs.getString("responsible_user_name"), rs.getLong("version"));
    }

    private EquipmentView equipmentView(ResultSet rs, int row) throws SQLException {
        return new EquipmentView(rs.getLong("id"), rs.getLong("lab_id"), rs.getString("lab_name"),
                rs.getString("asset_code"), rs.getString("category"), rs.getString("name"), rs.getString("model"),
                rs.getInt("total_quantity"), rs.getString("required_qualification"), rs.getString("status"),
                rs.getLong("version"));
    }

    private void requireManage(CurrentUser user, long labId) {
        if (!dataScope.canManageLab(user, labId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "LAB_SCOPE_DENIED", "无权管理该实验室");
        }
    }

    private long requireResponsibleUser(Long userId) {
        if (userId == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LAB_RESPONSIBLE_REQUIRED", "必须选择实验室负责人");
        }
        Boolean eligible = jdbcTemplate.queryForObject(
                "select exists(select 1 from sys_user u join sys_user_role ur on ur.user_id=u.id "
                        + "join sys_role r on r.id=ur.role_id where u.id=? and u.status='ACTIVE' and r.code='LAB_ADMIN')",
                Boolean.class,
                userId);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LAB_RESPONSIBLE_INVALID", "负责人必须是启用的实验室管理员");
        }
        return userId;
    }

    private void syncResponsibleManager(long labId, long userId) {
        jdbcTemplate.update(
                "insert into lab_manager(lab_id,user_id) values (?,?) on conflict do nothing", labId, userId);
    }

    private void requireUpdated(int updated, String notFoundCode, String notFoundMessage) {
        if (updated > 0) return;
        throw new AppException(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", notFoundMessage + "或版本已过期");
    }

    private AppException notFound(String code, String message) {
        return new AppException(HttpStatus.NOT_FOUND, code, message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化 JSON", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库标签 JSON 无效", exception);
        }
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record PeriodView(int periodNo, String name, LocalTime startTime, LocalTime endTime, boolean enabled, long version) {}

    public record LabView(long id, String code, String name, String building, String roomNo, int capacity,
            String labType, String description, String imageUrl, List<String> tags, String status,
            String studentApprovalMode, String teacherApprovalMode, boolean allowStudentBooking,
            int maxPeriodsPerUserDay, int advanceDays, int cancelBeforeMinutes, boolean requireCheckIn,
            Long responsibleUserId, String responsibleUserName, long version) {}

    public record CalendarSlotView(LocalDate bookingDate, int periodNo, String periodName, LocalTime startTime,
            LocalTime endTime, String slotStatus, String reason) {}

    public record OpenRuleView(long id, long labId, int dayOfWeek, int periodNo, LocalDate validFrom, LocalDate validTo) {}

    public record BlackoutView(long id, long labId, LocalDate bookingDate, int periodNo, String reason,
            long createdBy, String createdByName, OffsetDateTime createdAt) {}

    public record EquipmentView(long id, long labId, String labName, String assetCode, String category, String name,
            String model, int totalQuantity, String requiredQualification, String status, long version) {}
}
