package com.college.labbooking.attendance.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.reservation.domain.ReservationStateMachine;
import com.college.labbooking.reservation.domain.ReservationStatus;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.security.DataScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScope;
    private final ReservationStateMachine stateMachine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttendanceService(
            JdbcTemplate jdbcTemplate,
            DataScopeService dataScope,
            ReservationStateMachine stateMachine,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScope = dataScope;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AttendanceView checkIn(
            long reservationId, Integer actualParticipants, String note, CurrentUser operator, boolean administrator) {
        return checkInAt(reservationId, actualParticipants, note, operator, administrator, clock.instant());
    }

    @Transactional
    public AttendanceView checkInAt(
            long reservationId,
            Integer actualParticipants,
            String note,
            CurrentUser operator,
            boolean administrator,
            Instant now) {
        AttendanceRow row = lockReservation(reservationId);
        requireScope(row, operator, administrator);
        stateMachine.requireTransition(row.status(), ReservationStatus.IN_USE);
        Instant start = atBusinessInstant(row.bookingDate(), row.startTime());
        int beforeMinutes = settingInt("attendance.check-in-before-minutes");
        int graceMinutes = settingInt("attendance.check-in-grace-minutes");
        if (now.isBefore(start.minusSeconds(beforeMinutes * 60L)) || now.isAfter(start.plusSeconds(graceMinutes * 60L))) {
            throw conflict("CHECK_IN_WINDOW_CLOSED", "当前不在签到时间窗口内");
        }
        int updated = jdbcTemplate.update(
                "update reservation set status='IN_USE',checked_in_at=?,version=version+1,updated_at=now() "
                        + "where id=? and version=? and status='APPROVED'",
                OffsetDateTime.ofInstant(now, BUSINESS_ZONE), row.id(), row.version());
        if (updated == 0) throw conflict("RESERVATION_STATUS_CHANGED", "预约状态已被其他操作更新");
        jdbcTemplate.update("insert into attendance_record "
                        + "(reservation_id,check_in_at,check_in_method,operated_by,actual_participant_count,note) "
                        + "values (?,?,?,?,?,?) on conflict (reservation_id) do update set check_in_at=excluded.check_in_at,"
                        + "check_in_method=excluded.check_in_method,operated_by=excluded.operated_by,"
                        + "actual_participant_count=excluded.actual_participant_count,note=excluded.note,version=attendance_record.version+1",
                row.id(), OffsetDateTime.ofInstant(now, BUSINESS_ZONE), administrator ? "ADMIN" : "WEB", operator.id(),
                actualParticipants, note);
        history(row.id(), row.status(), ReservationStatus.IN_USE, administrator ? "管理员代签到" : "申请人签到",
                operator.id(), administrator ? "ADMIN" : "USER");
        auditService.record(operator.id(), operator.username(), administrator ? "ADMIN_CHECK_IN" : "CHECK_IN",
                "RESERVATION", String.valueOf(row.id()), true, Map.of("method", administrator ? "ADMIN" : "WEB"));
        return attendance(row.id());
    }

    @Transactional
    public AttendanceView checkOut(
            long reservationId, Integer actualParticipants, String note, CurrentUser operator, boolean administrator) {
        AttendanceRow row = lockReservation(reservationId);
        requireScope(row, operator, administrator);
        stateMachine.requireTransition(row.status(), ReservationStatus.COMPLETED);
        Instant now = clock.instant();
        int updated = jdbcTemplate.update(
                "update reservation set status='COMPLETED',checked_out_at=?,version=version+1,updated_at=now() "
                        + "where id=? and version=? and status='IN_USE'",
                OffsetDateTime.ofInstant(now, BUSINESS_ZONE), row.id(), row.version());
        if (updated == 0) throw conflict("RESERVATION_STATUS_CHANGED", "预约状态已被其他操作更新");
        jdbcTemplate.update("update attendance_record set check_out_at=?,check_out_method=?,operated_by=?,"
                        + "actual_participant_count=coalesce(?,actual_participant_count),note=coalesce(?,note),version=version+1 "
                        + "where reservation_id=?",
                OffsetDateTime.ofInstant(now, BUSINESS_ZONE), administrator ? "ADMIN" : "WEB", operator.id(),
                actualParticipants, note, row.id());
        history(row.id(), row.status(), ReservationStatus.COMPLETED, administrator ? "管理员代签退" : "申请人签退",
                operator.id(), administrator ? "ADMIN" : "USER");
        auditService.record(operator.id(), operator.username(), administrator ? "ADMIN_CHECK_OUT" : "CHECK_OUT",
                "RESERVATION", String.valueOf(row.id()), true, Map.of("method", administrator ? "ADMIN" : "WEB"));
        notifyAndPublish(row.applicantId(), "预约已完成", "预约使用已完成", "RESERVATION_COMPLETED", row.id());
        return attendance(row.id());
    }

    @Scheduled(fixedDelayString = "${app.jobs.attendance-delay-ms:60000}")
    @Transactional
    public void scheduledAttendanceSweep() {
        if (!tryJobLock("attendance-sweep")) return;
        processNoShows(clock.instant());
        processAutoCompletion(clock.instant());
    }

    @Transactional
    public int processNoShows(Instant now) {
        int graceMinutes = settingInt("attendance.check-in-grace-minutes");
        List<Long> candidates = jdbcTemplate.query(
                "select r.id from reservation r join course_period p on p.period_no=r.period_no "
                        + "where r.status='APPROVED' and r.booking_date<=? order by r.id",
                (rs, row) -> rs.getLong(1), LocalDate.ofInstant(now, BUSINESS_ZONE));
        int changed = 0;
        for (Long id : candidates) {
            AttendanceRow row = lockReservation(id);
            if (row.status() != ReservationStatus.APPROVED || !row.requireCheckIn()) continue;
            Instant deadline = atBusinessInstant(row.bookingDate(), row.startTime()).plusSeconds(graceMinutes * 60L);
            if (deadline.isAfter(now)) continue;
            stateMachine.requireTransition(row.status(), ReservationStatus.NO_SHOW);
            int updated = jdbcTemplate.update("update reservation set status='NO_SHOW',version=version+1,updated_at=now() "
                    + "where id=? and version=? and status='APPROVED'", row.id(), row.version());
            if (updated == 0) continue;
            jdbcTemplate.update("insert into user_violation "
                            + "(user_id,reservation_id,violation_type,description,points) values (?,?,'NO_SHOW','预约后未按时签到',1)",
                    row.applicantId(), row.id());
            jdbcTemplate.update("update sys_user set no_show_count=no_show_count+1,"
                            + "booking_frozen_until=case when no_show_count+1>=? then greatest(coalesce(booking_frozen_until,now()),now()) "
                            + "+ (? * interval '1 day') else booking_frozen_until end,updated_at=now() where id=?",
                    settingInt("violation.no-show-threshold"), settingInt("violation.freeze-days"), row.applicantId());
            history(row.id(), row.status(), ReservationStatus.NO_SHOW, "超过签到宽限期未签到", null, "SCHEDULER");
            notifyAndPublish(row.applicantId(), "预约已记为爽约", "超过签到宽限期未签到", "RESERVATION_NO_SHOW", row.id());
            auditService.record(null, "scheduler", "RESERVATION_NO_SHOW", "RESERVATION", String.valueOf(row.id()), true,
                    Map.of("applicantId", row.applicantId()));
            changed++;
        }
        return changed;
    }

    @Transactional
    public int processAutoCompletion(Instant now) {
        List<Long> candidates = jdbcTemplate.query(
                "select r.id from reservation r join course_period p on p.period_no=r.period_no "
                        + "where r.status in ('IN_USE','APPROVED') and r.booking_date<=? order by r.id",
                (rs, row) -> rs.getLong(1), LocalDate.ofInstant(now, BUSINESS_ZONE));
        int changed = 0;
        for (Long id : candidates) {
            AttendanceRow row = lockReservation(id);
            if (row.status() != ReservationStatus.IN_USE
                    && !(row.status() == ReservationStatus.APPROVED && !row.requireCheckIn())) continue;
            if (atBusinessInstant(row.bookingDate(), row.endTime()).isAfter(now)) continue;
            ReservationStatus from = row.status();
            ReservationStatus target = ReservationStatus.COMPLETED;
            stateMachine.requireTransition(from, target);
            int updated = jdbcTemplate.update("update reservation set status='COMPLETED',checked_out_at=coalesce(checked_out_at,?),"
                            + "version=version+1,updated_at=now() where id=? and version=? and status=?",
                    OffsetDateTime.ofInstant(now, BUSINESS_ZONE), row.id(), row.version(), from.name());
            if (updated == 0) continue;
            history(row.id(), from, target, "预约课次结束后自动完成", null, "SCHEDULER");
            notifyAndPublish(row.applicantId(), "预约已自动完成", "预约课次已结束", "RESERVATION_COMPLETED", row.id());
            changed++;
        }
        return changed;
    }

    public AttendanceView attendance(long reservationId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select r.id,r.status,a.check_in_at,a.check_out_at,a.check_in_method,a.check_out_method,"
                            + "a.operated_by,a.actual_participant_count,a.note from reservation r "
                            + "left join attendance_record a on a.reservation_id=r.id where r.id=?",
                    (rs, row) -> new AttendanceView(rs.getLong("id"), rs.getString("status"),
                            rs.getObject("check_in_at", OffsetDateTime.class), rs.getObject("check_out_at", OffsetDateTime.class),
                            rs.getString("check_in_method"), rs.getString("check_out_method"),
                            nullableLong(rs, "operated_by"), nullableInt(rs, "actual_participant_count"), rs.getString("note")),
                    reservationId);
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "预约不存在");
        }
    }

    private AttendanceRow lockReservation(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select r.id,r.applicant_id,r.lab_id,r.booking_date,r.period_no,r.status,r.version,p.start_time,p.end_time,"
                            + "l.require_check_in from reservation r join course_period p on p.period_no=r.period_no "
                            + "join lab l on l.id=r.lab_id where r.id=? for update of r",
                    (rs, row) -> new AttendanceRow(rs.getLong("id"), rs.getLong("applicant_id"), rs.getLong("lab_id"),
                            rs.getDate("booking_date").toLocalDate(), rs.getInt("period_no"),
                            ReservationStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                            rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                            rs.getBoolean("require_check_in")), id);
        } catch (EmptyResultDataAccessException exception) {
            throw new AppException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "预约不存在");
        }
    }

    private void requireScope(AttendanceRow row, CurrentUser user, boolean administrator) {
        if (administrator) {
            if (!dataScope.canManageLab(user, row.labId())) throw forbidden("LAB_SCOPE_DENIED", "无权操作该实验室预约");
        } else if (row.applicantId() != user.id()) {
            throw forbidden("RESERVATION_SCOPE_DENIED", "只能操作本人预约");
        }
    }

    private void history(long id, ReservationStatus from, ReservationStatus to, String reason, Long operatorId, String source) {
        jdbcTemplate.update("insert into reservation_status_history "
                        + "(reservation_id,from_status,to_status,reason,operator_id,source) values (?,?,?,?,?,?)",
                id, from.name(), to.name(), reason, operatorId, source);
    }

    private void notifyAndPublish(long recipientId, String title, String content, String eventType, long reservationId) {
        jdbcTemplate.update("insert into notification (recipient_id,notification_type,title,content,related_type,related_id) "
                        + "values (?,?,?,?, 'RESERVATION', ?)", recipientId, eventType, title, content, reservationId);
        jdbcTemplate.update("insert into outbox_event (id,aggregate_type,aggregate_id,event_type,payload) "
                        + "values (?,'RESERVATION',?,?,cast(? as jsonb))",
                UUID.randomUUID(), String.valueOf(reservationId), eventType,
                json(Map.of("reservationId", reservationId, "recipientId", recipientId, "title", title)));
    }

    private int settingInt(String key) {
        try {
            Integer value = jdbcTemplate.queryForObject(
                    "select (setting_value #>> '{}')::integer from system_setting where setting_key=?", Integer.class, key);
            return value == null ? 0 : value;
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("缺少系统参数：" + key, exception);
        }
    }

    private boolean tryJobLock(String name) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class, name);
        return Boolean.TRUE.equals(acquired);
    }

    private Instant atBusinessInstant(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(BUSINESS_ZONE).toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化通知事件", exception);
        }
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    private AppException forbidden(String code, String message) {
        return new AppException(HttpStatus.FORBIDDEN, code, message);
    }

    private record AttendanceRow(long id, long applicantId, long labId, LocalDate bookingDate, int periodNo,
            ReservationStatus status, long version, LocalTime startTime, LocalTime endTime, boolean requireCheckIn) {}

    public record AttendanceView(long reservationId, String status, OffsetDateTime checkInAt, OffsetDateTime checkOutAt,
            String checkInMethod, String checkOutMethod, Long operatedBy, Integer actualParticipantCount, String note) {}
}
