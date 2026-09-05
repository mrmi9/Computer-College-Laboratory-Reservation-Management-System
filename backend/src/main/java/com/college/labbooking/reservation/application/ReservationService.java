package com.college.labbooking.reservation.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.auth.application.TokenDigests;
import com.college.labbooking.common.api.PageView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.reservation.domain.ReservationStateMachine;
import com.college.labbooking.reservation.domain.ReservationStatus;
import com.college.labbooking.reservation.web.ReservationController.CreateReservationRequest;
import com.college.labbooking.reservation.web.ReservationController.EquipmentItemRequest;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.security.DataScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<ReservationStatus> EFFECTIVE = Set.of(
            ReservationStatus.APPROVED, ReservationStatus.IN_USE, ReservationStatus.COMPLETED);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TokenDigests tokenDigests;
    private final ReservationStateMachine stateMachine;
    private final DataScopeService dataScope;
    private final AuditService auditService;
    private final Clock clock;

    public ReservationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TokenDigests tokenDigests,
            ReservationStateMachine stateMachine,
            DataScopeService dataScope,
            AuditService auditService,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tokenDigests = tokenDigests;
        this.stateMachine = stateMachine;
        this.dataScope = dataScope;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<AvailableLabView> availability(
            LocalDate bookingDate, int periodNo, int capacity, List<Long> equipmentIds, CurrentUser user) {
        String applicantType = applicantType(user);
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (bookingDate.isBefore(today)) {
            throw conflict("BOOKING_DATE_PAST", "预约日期不能早于今天");
        }
        int dayOfWeek = bookingDate.getDayOfWeek().getValue();
        List<AvailableLabView> candidates = jdbcTemplate.query(
                "select l.id,l.code,l.name,l.building,l.room_no,l.capacity,l.lab_type,l.tags,l.advance_days "
                        + "from lab l where l.status='ACTIVE' and l.capacity>=? "
                        + "and (?='TEACHER' or l.allow_student_booking=true) "
                        + "and ? <= current_date + l.advance_days "
                        + "and exists(select 1 from course_period p where p.period_no=? and p.enabled=true) "
                        + "and exists(select 1 from lab_open_rule o where o.lab_id=l.id and o.day_of_week=? and o.period_no=? "
                        + "and (o.valid_from is null or o.valid_from<=?) and (o.valid_to is null or o.valid_to>=?)) "
                        + "and not exists(select 1 from lab_blackout b where b.lab_id=l.id and b.booking_date=? and b.period_no=?) "
                        + "and not exists(select 1 from reservation r where r.lab_id=l.id and r.booking_date=? and r.period_no=? "
                        + "and r.status in ('APPROVED','IN_USE','COMPLETED')) order by l.code",
                (rs, row) -> new AvailableLabView(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("building"),
                        rs.getString("room_no"), rs.getInt("capacity"), rs.getString("lab_type"),
                        jsonStringList(rs.getString("tags"))),
                capacity, applicantType, bookingDate, periodNo, dayOfWeek, periodNo, bookingDate, bookingDate,
                bookingDate, periodNo, bookingDate, periodNo);
        if (equipmentIds.isEmpty()) return candidates;
        Set<Long> requested = new HashSet<>(equipmentIds);
        if (requested.size() != equipmentIds.size()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "DUPLICATE_EQUIPMENT", "设备筛选条件不能重复");
        }
        return candidates.stream().filter(lab -> hasAvailableEquipment(lab.id(), requested)).toList();
    }

    @Transactional
    public ReservationView create(CreateReservationRequest request, String idempotencyKey, CurrentUser user) {
        String applicantType = applicantType(user);
        String requestHash = tokenDigests.sha256(json(request));
        Long replayId = beginIdempotent(user.id(), "RESERVATION_CREATE", idempotencyKey, requestHash);
        if (replayId != null) return view(replayId);

        LabPolicy lab = lockLab(request.labId());
        validateBookingRules(lab, request.bookingDate(), request.periodNo(), request.participantCount(), applicantType, user.id(), true);
        List<LockedEquipment> equipment = lockAndValidateEquipment(lab.id(), request.equipmentItems(), user.id());
        ReservationStatus status = approvalMode(lab, applicantType).equals("AUTO")
                ? ReservationStatus.APPROVED
                : ReservationStatus.PENDING_APPROVAL;
        if (EFFECTIVE.contains(status)) {
            ensureSlotAvailable(lab.id(), request.bookingDate(), request.periodNo(), null);
            ensureEquipmentCapacity(equipment, request.equipmentItems(), request.bookingDate(), request.periodNo(), null);
        }
        String reservationNo = "R" + request.bookingDate().toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Long id = jdbcTemplate.queryForObject(
                "insert into reservation (reservation_no,applicant_id,applicant_type,lab_id,title,purpose,participant_count,"
                        + "booking_date,period_no,status,contact_phone,project_or_course,remark) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, reservationNo, user.id(), applicantType, lab.id(), request.title().trim(), request.purpose().trim(),
                request.participantCount(), request.bookingDate(), request.periodNo(), status.name(),
                request.contactPhone().trim(), request.projectOrCourse().trim(), request.remark());
        for (EquipmentItemRequest item : request.equipmentItems()) {
            jdbcTemplate.update("insert into reservation_equipment (reservation_id,equipment_id,quantity) values (?,?,?)",
                    id, item.equipmentId(), item.quantity());
        }
        history(id, null, status, status == ReservationStatus.APPROVED ? "按实验室策略自动审批" : "提交预约申请",
                user.id(), status == ReservationStatus.APPROVED ? "AUTO_APPROVAL" : "USER");
        String title = status == ReservationStatus.APPROVED ? "预约已自动通过" : "预约提交成功";
        notifyAndPublish(user.id(), title, "预约 " + reservationNo + " 已进入状态 " + status, "RESERVATION_CREATED", id);
        completeIdempotent(user.id(), "RESERVATION_CREATE", idempotencyKey, id);
        auditService.record(user.id(), user.username(), "RESERVATION_CREATED", "RESERVATION", String.valueOf(id), true,
                Map.of("applicantType", applicantType, "status", status.name(), "labId", lab.id()));
        return view(id);
    }

    public PageView<ReservationView> mine(CurrentUser user, String status, int page, int size) {
        String normalized = normalize(status);
        String where = " where r.applicant_id=? and (?='' or r.status=?)";
        Long total = jdbcTemplate.queryForObject("select count(*) from reservation r" + where, Long.class,
                user.id(), normalized, normalized);
        List<ReservationView> items = jdbcTemplate.query(baseSelect() + where + " order by r.created_at desc limit ? offset ?",
                this::reservationView, user.id(), normalized, normalized, size, (long) page * size);
        items = attachEquipment(items);
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    public ReservationView detail(long id, CurrentUser user) {
        ReservationView item = view(id);
        if (item.applicantId() != user.id() && !dataScope.canManageLab(user, item.labId())) {
            throw forbidden("RESERVATION_SCOPE_DENIED", "无权查看该预约");
        }
        return item;
    }

    @Transactional
    public ReservationView cancelByApplicant(long id, String reason, CurrentUser user) {
        ReservationRow row = lockReservation(id);
        if (row.applicantId() != user.id()) throw forbidden("RESERVATION_SCOPE_DENIED", "只能取消本人预约");
        stateMachine.requireTransition(row.status(), ReservationStatus.CANCELLED);
        LocalTime start = periodStart(row.periodNo());
        Instant deadline = LocalDateTime.of(row.bookingDate(), start).atZone(BUSINESS_ZONE)
                .minusMinutes(row.cancelBeforeMinutes()).toInstant();
        if (!clock.instant().isBefore(deadline)) {
            throw conflict("CANCELLATION_DEADLINE_PASSED", "已超过申请人取消截止时间，请联系管理员");
        }
        changeStatus(row, ReservationStatus.CANCELLED, reason.trim(), user, "USER", false);
        return view(id);
    }

    public PageView<ReservationView> managed(
            CurrentUser user, Long labId, String applicantType, String status, int page, int size) {
        if (labId != null && !dataScope.canManageLab(user, labId)) {
            throw forbidden("LAB_SCOPE_DENIED", "无权查看该实验室预约");
        }
        String type = normalize(applicantType);
        String state = normalize(status);
        String scope = user.hasRole("SYSTEM_ADMIN")
                ? ""
                : " and exists(select 1 from lab_manager lm where lm.lab_id=r.lab_id and lm.user_id=?)";
        String where = " where (?='' or r.applicant_type=?) and (?='' or r.status=?)";
        List<Object> params = new ArrayList<>(List.of(type, type, state, state));
        if (labId != null) {
            where += " and r.lab_id=?";
            params.add(labId);
        }
        where += scope;
        if (!user.hasRole("SYSTEM_ADMIN")) params.add(user.id());
        Long total = jdbcTemplate.queryForObject("select count(*) from reservation r" + where, Long.class, params.toArray());
        params.add(size);
        params.add((long) page * size);
        List<ReservationView> items = jdbcTemplate.query(baseSelect() + where + " order by r.created_at desc limit ? offset ?",
                this::reservationView, params.toArray());
        return new PageView<>(attachEquipment(items), page, size, total == null ? 0 : total);
    }

    @Transactional
    public ReservationView approve(
            long id, long expectedVersion, String comment, String idempotencyKey, CurrentUser user) {
        String hash = tokenDigests.sha256(id + ":" + expectedVersion + ":" + String.valueOf(comment));
        Long replayId = beginIdempotent(user.id(), "RESERVATION_APPROVE", idempotencyKey, hash);
        if (replayId != null) return view(replayId);
        ReservationRow row = lockReservation(id);
        requireManage(user, row.labId());
        requireVersion(row, expectedVersion);
        stateMachine.requireTransition(row.status(), ReservationStatus.APPROVED);
        LabPolicy lab = lockLab(row.labId());
        validateBookingRules(lab, row.bookingDate(), row.periodNo(), row.participantCount(), row.applicantType(), row.applicantId(), false);
        ensureSlotAvailable(row.labId(), row.bookingDate(), row.periodNo(), row.id());
        List<EquipmentItemRequest> items = equipmentRequests(row.id());
        List<LockedEquipment> equipment = lockAndValidateEquipment(row.labId(), items, row.applicantId());
        ensureEquipmentCapacity(equipment, items, row.bookingDate(), row.periodNo(), row.id());
        changeStatus(row, ReservationStatus.APPROVED, comment, user, "ADMIN", true);
        recordApproval(row.id(), user.id(), "APPROVE", row.status(), ReservationStatus.APPROVED, comment, idempotencyKey);
        completeIdempotent(user.id(), "RESERVATION_APPROVE", idempotencyKey, id);
        return view(id);
    }

    @Transactional
    public ReservationView reject(
            long id, long expectedVersion, String reason, String idempotencyKey, CurrentUser user) {
        String hash = tokenDigests.sha256(id + ":" + expectedVersion + ":" + reason);
        Long replayId = beginIdempotent(user.id(), "RESERVATION_REJECT", idempotencyKey, hash);
        if (replayId != null) return view(replayId);
        ReservationRow row = lockReservation(id);
        requireManage(user, row.labId());
        requireVersion(row, expectedVersion);
        stateMachine.requireTransition(row.status(), ReservationStatus.REJECTED);
        changeStatus(row, ReservationStatus.REJECTED, reason.trim(), user, "ADMIN", true);
        recordApproval(row.id(), user.id(), "REJECT", row.status(), ReservationStatus.REJECTED, reason, idempotencyKey);
        completeIdempotent(user.id(), "RESERVATION_REJECT", idempotencyKey, id);
        return view(id);
    }

    @Transactional
    public ReservationView cancelByAdmin(long id, long expectedVersion, String reason, CurrentUser user) {
        ReservationRow row = lockReservation(id);
        requireManage(user, row.labId());
        requireVersion(row, expectedVersion);
        stateMachine.requireTransition(row.status(), ReservationStatus.CANCELLED);
        changeStatus(row, ReservationStatus.CANCELLED, reason.trim(), user, "ADMIN", true);
        recordApproval(row.id(), user.id(), "ADMIN_CANCEL", row.status(), ReservationStatus.CANCELLED, reason, null);
        return view(id);
    }

    public List<HistoryView> history(long id, CurrentUser user) {
        ReservationView reservation = view(id);
        requireManage(user, reservation.labId());
        return jdbcTemplate.query(
                "select h.id,h.from_status,h.to_status,h.reason,h.operator_id,u.real_name operator_name,h.source,h.created_at "
                        + "from reservation_status_history h left join sys_user u on u.id=h.operator_id "
                        + "where h.reservation_id=? order by h.created_at,h.id",
                (rs, row) -> new HistoryView(rs.getLong("id"), rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("reason"), nullableLong(rs, "operator_id"), rs.getString("operator_name"),
                        rs.getString("source"), rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    private void changeStatus(
            ReservationRow row, ReservationStatus target, String reason, CurrentUser operator, String source, boolean notifyApplicant) {
        int updated = jdbcTemplate.update(
                "update reservation set status=?,cancellation_reason=case when ?='CANCELLED' then ? else cancellation_reason end,"
                        + "approved_by=case when ?='APPROVED' then ? else approved_by end,"
                        + "approved_at=case when ?='APPROVED' then now() else approved_at end,version=version+1,updated_at=now() "
                        + "where id=? and version=?",
                target.name(), target.name(), reason, target.name(), operator.id(), target.name(), row.id(), row.version());
        if (updated == 0) throw conflict("RESOURCE_VERSION_CONFLICT", "预约版本已被更新");
        history(row.id(), row.status(), target, reason, operator.id(), source);
        if (notifyApplicant) {
            notifyAndPublish(row.applicantId(), "预约状态更新", "预约已变更为 " + target,
                    "RESERVATION_" + target.name(), row.id());
        }
        auditService.record(operator.id(), operator.username(), "RESERVATION_" + target.name(), "RESERVATION",
                String.valueOf(row.id()), true, Map.of("from", row.status().name(), "reason", reason == null ? "" : reason));
    }

    private void validateBookingRules(
            LabPolicy lab, LocalDate date, int periodNo, int participants, String applicantType, long applicantId,
            boolean enforceDailyLimit) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (date.isBefore(today)) throw conflict("BOOKING_DATE_PAST", "预约日期不能早于今天");
        if (date.isAfter(today.plusDays(lab.advanceDays()))) {
            throw conflict("BOOKING_TOO_FAR_AHEAD", "预约日期超过实验室允许的提前天数");
        }
        if (!lab.status().equals("ACTIVE")) throw conflict("LAB_UNAVAILABLE", "实验室当前不可预约");
        if (participants > lab.capacity()) throw conflict("CAPACITY_EXCEEDED", "参加人数超过实验室容量");
        if (applicantType.equals("STUDENT") && !lab.allowStudentBooking()) {
            throw forbidden("STUDENT_BOOKING_DISABLED", "该实验室不接受学生预约");
        }
        Boolean periodEnabled = jdbcTemplate.queryForObject(
                "select exists(select 1 from course_period where period_no=? and enabled=true)", Boolean.class, periodNo);
        if (!Boolean.TRUE.equals(periodEnabled)) throw conflict("PERIOD_DISABLED", "所选课次未启用");
        Boolean open = jdbcTemplate.queryForObject(
                "select exists(select 1 from lab_open_rule where lab_id=? and day_of_week=? and period_no=? "
                        + "and (valid_from is null or valid_from<=?) and (valid_to is null or valid_to>=?))",
                Boolean.class, lab.id(), date.getDayOfWeek().getValue(), periodNo, date, date);
        if (!Boolean.TRUE.equals(open)) throw conflict("LAB_NOT_OPEN", "所选日期和课次不在实验室开放规则内");
        Boolean blackout = jdbcTemplate.queryForObject(
                "select exists(select 1 from lab_blackout where lab_id=? and booking_date=? and period_no=?)",
                Boolean.class, lab.id(), date, periodNo);
        if (Boolean.TRUE.equals(blackout)) throw conflict("LAB_BLACKOUT", "所选课次已停用");
        if (enforceDailyLimit) {
            Integer daily = jdbcTemplate.queryForObject(
                    "select count(*) from reservation where applicant_id=? and booking_date=? "
                            + "and status not in ('REJECTED','CANCELLED')",
                    Integer.class, applicantId, date);
            if (daily != null && daily >= lab.maxPeriodsPerUserDay()) {
                throw conflict("DAILY_BOOKING_LIMIT", "已达到该实验室配置的每日预约课次上限");
            }
        }
        if (applicantType.equals("STUDENT")) {
            Instant frozenUntil = jdbcTemplate.queryForObject(
                    "select booking_frozen_until from sys_user where id=?", (rs, row) -> {
                        OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                        return value == null ? null : value.toInstant();
                    }, applicantId);
            if (frozenUntil != null && frozenUntil.isAfter(clock.instant())) {
                throw forbidden("BOOKING_FROZEN", "账号预约权限暂时冻结");
            }
        }
    }

    private List<LockedEquipment> lockAndValidateEquipment(
            long labId, List<EquipmentItemRequest> items, long applicantId) {
        if (items.isEmpty()) return List.of();
        Set<Long> ids = new HashSet<>();
        for (EquipmentItemRequest item : items) {
            if (!ids.add(item.equipmentId())) {
                throw new AppException(HttpStatus.BAD_REQUEST, "DUPLICATE_EQUIPMENT", "同一设备只能申请一次");
            }
        }
        List<Long> sorted = ids.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(sorted.size(), "?"));
        List<LockedEquipment> equipment = jdbcTemplate.query(
                "select id,lab_id,total_quantity,required_qualification,status from equipment where id in ("
                        + placeholders + ") order by id for update",
                (rs, row) -> new LockedEquipment(rs.getLong("id"), rs.getLong("lab_id"), rs.getInt("total_quantity"),
                        rs.getString("required_qualification"), rs.getString("status")), sorted.toArray());
        if (equipment.size() != sorted.size()) throw conflict("EQUIPMENT_NOT_FOUND", "申请的设备不存在");
        for (LockedEquipment item : equipment) {
            if (item.labId() != labId) throw conflict("EQUIPMENT_LAB_MISMATCH", "设备不属于所选实验室");
            if (!item.status().equals("AVAILABLE")) throw conflict("EQUIPMENT_UNAVAILABLE", "设备当前不可用");
            if (item.requiredQualification() != null) {
                Boolean qualified = jdbcTemplate.queryForObject(
                        "select exists(select 1 from user_qualification where user_id=? and qualification_code=? "
                                + "and (valid_until is null or valid_until>=current_date))",
                        Boolean.class, applicantId, item.requiredQualification());
                if (!Boolean.TRUE.equals(qualified)) {
                    throw forbidden("EQUIPMENT_QUALIFICATION_REQUIRED", "申请设备需要有效资质：" + item.requiredQualification());
                }
            }
        }
        return equipment;
    }

    private void ensureEquipmentCapacity(
            List<LockedEquipment> equipment,
            List<EquipmentItemRequest> requests,
            LocalDate date,
            int periodNo,
            Long excludingReservationId) {
        Map<Long, Integer> quantities = requests.stream().collect(java.util.stream.Collectors.toMap(
                EquipmentItemRequest::equipmentId, EquipmentItemRequest::quantity));
        for (LockedEquipment item : equipment) {
            String sql = "select coalesce(sum(re.quantity),0) from reservation_equipment re "
                    + "join reservation r on r.id=re.reservation_id where re.equipment_id=? and r.booking_date=? "
                    + "and r.period_no=? and r.status in ('APPROVED','IN_USE','COMPLETED')";
            Integer used = excludingReservationId == null
                    ? jdbcTemplate.queryForObject(sql, Integer.class, item.id(), date, periodNo)
                    : jdbcTemplate.queryForObject(sql + " and r.id<>?", Integer.class,
                            item.id(), date, periodNo, excludingReservationId);
            if ((used == null ? 0 : used) + quantities.get(item.id()) > item.totalQuantity()) {
                throw conflict("EQUIPMENT_CAPACITY_EXCEEDED", "设备可用数量不足");
            }
        }
    }

    private void ensureSlotAvailable(long labId, LocalDate date, int periodNo, Long excludingReservationId) {
        String sql = "select exists(select 1 from reservation where lab_id=? and booking_date=? and period_no=? "
                + "and status in ('APPROVED','IN_USE','COMPLETED')";
        Boolean occupied = excludingReservationId == null
                ? jdbcTemplate.queryForObject(sql + ")", Boolean.class, labId, date, periodNo)
                : jdbcTemplate.queryForObject(sql + " and id<>?)", Boolean.class,
                        labId, date, periodNo, excludingReservationId);
        if (Boolean.TRUE.equals(occupied)) throw conflict("RESERVATION_SLOT_CONFLICT", "该实验室在所选课次已被占用");
    }

    private LabPolicy lockLab(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select id,status,capacity,allow_student_booking,max_periods_per_user_day,advance_days,"
                            + "cancel_before_minutes,student_approval_mode,teacher_approval_mode from lab where id=? for update",
                    (rs, row) -> new LabPolicy(rs.getLong("id"), rs.getString("status"), rs.getInt("capacity"),
                            rs.getBoolean("allow_student_booking"), rs.getInt("max_periods_per_user_day"),
                            rs.getInt("advance_days"), rs.getInt("cancel_before_minutes"),
                            rs.getString("student_approval_mode"), rs.getString("teacher_approval_mode")), id);
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "LAB_NOT_FOUND", "实验室不存在");
        }
    }

    private ReservationRow lockReservation(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select r.id,r.applicant_id,r.applicant_type,r.lab_id,r.booking_date,r.period_no,r.participant_count,"
                            + "r.status,r.version,l.cancel_before_minutes from reservation r join lab l on l.id=r.lab_id "
                            + "where r.id=? for update of r",
                    (rs, row) -> new ReservationRow(rs.getLong("id"), rs.getLong("applicant_id"),
                            rs.getString("applicant_type"), rs.getLong("lab_id"), rs.getDate("booking_date").toLocalDate(),
                            rs.getInt("period_no"), rs.getInt("participant_count"),
                            ReservationStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                            rs.getInt("cancel_before_minutes")), id);
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "预约不存在");
        }
    }

    private ReservationView view(long id) {
        try {
            ReservationView base = jdbcTemplate.queryForObject(baseSelect() + " where r.id=?", this::reservationView, id);
            return withEquipment(base, equipmentViews(id));
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "预约不存在");
        }
    }

    private String baseSelect() {
        return "select r.id,r.reservation_no,r.applicant_id,u.real_name applicant_name,r.applicant_type,r.lab_id,"
                + "l.name lab_name,l.building,l.room_no,r.title,r.purpose,r.participant_count,r.booking_date,r.period_no,"
                + "p.name period_name,p.start_time,p.end_time,r.status,r.contact_phone,r.project_or_course,r.remark,"
                + "r.cancellation_reason,r.version,r.created_at,r.updated_at from reservation r "
                + "join sys_user u on u.id=r.applicant_id join lab l on l.id=r.lab_id "
                + "join course_period p on p.period_no=r.period_no";
    }

    private ReservationView reservationView(ResultSet rs, int row) throws SQLException {
        ReservationStatus status = ReservationStatus.valueOf(rs.getString("status"));
        return new ReservationView(rs.getLong("id"), rs.getString("reservation_no"), rs.getLong("applicant_id"),
                rs.getString("applicant_name"), rs.getString("applicant_type"), rs.getLong("lab_id"),
                rs.getString("lab_name"), rs.getString("building"), rs.getString("room_no"), rs.getString("title"),
                rs.getString("purpose"), rs.getInt("participant_count"), rs.getDate("booking_date").toLocalDate(),
                rs.getInt("period_no"), rs.getString("period_name"), rs.getTime("start_time").toLocalTime(),
                rs.getTime("end_time").toLocalTime(), status.name(), rs.getString("contact_phone"),
                rs.getString("project_or_course"), rs.getString("remark"), rs.getString("cancellation_reason"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), nextAction(status), List.of());
    }

    private List<ReservationView> attachEquipment(List<ReservationView> reservations) {
        return reservations.stream().map(item -> withEquipment(item, equipmentViews(item.id()))).toList();
    }

    private ReservationView withEquipment(ReservationView item, List<EquipmentItemView> equipment) {
        return new ReservationView(item.id(), item.reservationNo(), item.applicantId(), item.applicantName(),
                item.applicantType(), item.labId(), item.labName(), item.building(), item.roomNo(), item.title(),
                item.purpose(), item.participantCount(), item.bookingDate(), item.periodNo(), item.periodName(),
                item.startTime(), item.endTime(), item.status(), item.contactPhone(), item.projectOrCourse(), item.remark(),
                item.cancellationReason(), item.version(), item.createdAt(), item.updatedAt(), item.nextAction(), equipment);
    }

    private List<EquipmentItemView> equipmentViews(long reservationId) {
        return jdbcTemplate.query(
                "select e.id,e.asset_code,e.name,re.quantity from reservation_equipment re "
                        + "join equipment e on e.id=re.equipment_id where re.reservation_id=? order by e.id",
                (rs, row) -> new EquipmentItemView(rs.getLong("id"), rs.getString("asset_code"),
                        rs.getString("name"), rs.getInt("quantity")), reservationId);
    }

    private List<EquipmentItemRequest> equipmentRequests(long reservationId) {
        return jdbcTemplate.query("select equipment_id,quantity from reservation_equipment where reservation_id=? order by equipment_id",
                (rs, row) -> new EquipmentItemRequest(rs.getLong("equipment_id"), rs.getInt("quantity")), reservationId);
    }

    private void history(long reservationId, ReservationStatus from, ReservationStatus to, String reason, Long operatorId, String source) {
        jdbcTemplate.update("insert into reservation_status_history "
                        + "(reservation_id,from_status,to_status,reason,operator_id,source) values (?,?,?,?,?,?)",
                reservationId, from == null ? null : from.name(), to.name(), reason, operatorId, source);
    }

    private void recordApproval(long reservationId, long approverId, String action, ReservationStatus from,
            ReservationStatus to, String comment, String key) {
        jdbcTemplate.update("insert into approval_record "
                        + "(reservation_id,approver_id,action,from_status,to_status,comment,idempotency_key) values (?,?,?,?,?,?,?)",
                reservationId, approverId, action, from.name(), to.name(), comment, key);
    }

    private void notifyAndPublish(long recipientId, String title, String content, String eventType, long reservationId) {
        jdbcTemplate.update("insert into notification (recipient_id,notification_type,title,content,related_type,related_id) "
                        + "values (?,?,?,?,?,?)",
                recipientId, eventType, title, content, "RESERVATION", reservationId);
        jdbcTemplate.update("insert into outbox_event (id,aggregate_type,aggregate_id,event_type,payload) "
                        + "values (?, 'RESERVATION', ?, ?, cast(? as jsonb))",
                UUID.randomUUID(), String.valueOf(reservationId), eventType,
                json(Map.of("reservationId", reservationId, "recipientId", recipientId, "title", title)));
    }

    private Long beginIdempotent(long userId, String operation, String key, String requestHash) {
        if (key == null || key.isBlank()) return null;
        if (key.length() > 128) throw new AppException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "幂等键过长");
        String lockKey = userId + ":" + operation + ":" + key;
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (RowCallbackHandler) rs -> {}, lockKey);
        List<IdempotencyRow> existing = jdbcTemplate.query(
                "select request_hash,processing_state,resource_id from idempotency_record "
                        + "where user_id=? and operation=? and idempotency_key=?",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash").trim(), rs.getString("processing_state"),
                        rs.getString("resource_id")), userId, operation, key);
        if (!existing.isEmpty()) {
            IdempotencyRow record = existing.getFirst();
            if (!record.requestHash().equals(requestHash)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "同一幂等键不能用于不同请求");
            }
            if (record.state().equals("COMPLETED") && record.resourceId() != null) {
                return Long.valueOf(record.resourceId());
            }
            throw conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS", "相同请求正在处理中");
        }
        jdbcTemplate.update("insert into idempotency_record "
                        + "(user_id,operation,idempotency_key,request_hash,processing_state,expires_at) "
                        + "values (?,?,?,?, 'PROCESSING', now()+interval '1 day')",
                userId, operation, key, requestHash);
        return null;
    }

    private void completeIdempotent(long userId, String operation, String key, long resourceId) {
        if (key == null || key.isBlank()) return;
        jdbcTemplate.update("update idempotency_record set processing_state='COMPLETED',response_status=200,"
                        + "resource_type='RESERVATION',resource_id=? where user_id=? and operation=? and idempotency_key=?",
                String.valueOf(resourceId), userId, operation, key);
    }

    private boolean hasAvailableEquipment(long labId, Set<Long> equipmentIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(equipmentIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(labId);
        params.addAll(equipmentIds.stream().sorted().toList());
        Integer count = jdbcTemplate.queryForObject("select count(*) from equipment where lab_id=? and status='AVAILABLE' "
                + "and id in (" + placeholders + ")", Integer.class, params.toArray());
        return count != null && count == equipmentIds.size();
    }

    private String applicantType(CurrentUser user) {
        if (user.hasRole("TEACHER")) return "TEACHER";
        if (user.hasRole("STUDENT")) return "STUDENT";
        throw forbidden("BUSINESS_IDENTITY_REQUIRED", "纯管理员账号不能创建预约");
    }

    private String approvalMode(LabPolicy lab, String applicantType) {
        return applicantType.equals("TEACHER") ? lab.teacherApprovalMode() : lab.studentApprovalMode();
    }

    private void requireManage(CurrentUser user, long labId) {
        if (!dataScope.canManageLab(user, labId)) throw forbidden("LAB_SCOPE_DENIED", "无权处理该实验室预约");
    }

    private void requireVersion(ReservationRow row, long expectedVersion) {
        if (row.version() != expectedVersion) throw conflict("RESOURCE_VERSION_CONFLICT", "预约版本已过期，请刷新后重试");
    }

    private LocalTime periodStart(int periodNo) {
        try {
            return jdbcTemplate.queryForObject("select start_time from course_period where period_no=?",
                    (rs, row) -> rs.getTime(1).toLocalTime(), periodNo);
        } catch (EmptyResultDataAccessException exception) {
            throw conflict("PERIOD_DISABLED", "课次不存在");
        }
    }

    private String nextAction(ReservationStatus status) {
        return switch (status) {
            case PENDING_APPROVAL -> "WAITING_APPROVAL";
            case APPROVED -> "CHECK_IN";
            case IN_USE -> "CHECK_OUT";
            default -> "NONE";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    private AppException forbidden(String code, String message) {
        return new AppException(HttpStatus.FORBIDDEN, code, message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化业务数据", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> jsonStringList(String value) {
        try {
            return objectMapper.readValue(value, List.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("实验室标签 JSON 无效", exception);
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record LabPolicy(long id, String status, int capacity, boolean allowStudentBooking,
            int maxPeriodsPerUserDay, int advanceDays, int cancelBeforeMinutes,
            String studentApprovalMode, String teacherApprovalMode) {}

    private record ReservationRow(long id, long applicantId, String applicantType, long labId, LocalDate bookingDate,
            int periodNo, int participantCount, ReservationStatus status, long version, int cancelBeforeMinutes) {}

    private record LockedEquipment(long id, long labId, int totalQuantity, String requiredQualification, String status) {}

    private record IdempotencyRow(String requestHash, String state, String resourceId) {}

    public record AvailableLabView(long id, String code, String name, String building, String roomNo,
            int capacity, String labType, List<String> tags) {}

    public record EquipmentItemView(long equipmentId, String assetCode, String name, int quantity) {}

    public record ReservationView(long id, String reservationNo, long applicantId, String applicantName,
            String applicantType, long labId, String labName, String building, String roomNo, String title,
            String purpose, int participantCount, LocalDate bookingDate, int periodNo, String periodName,
            LocalTime startTime, LocalTime endTime, String status, String contactPhone, String projectOrCourse,
            String remark, String cancellationReason, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            String nextAction, List<EquipmentItemView> equipmentItems) {}

    public record HistoryView(long id, String fromStatus, String toStatus, String reason, Long operatorId,
            String operatorName, String source, OffsetDateTime createdAt) {}
}
