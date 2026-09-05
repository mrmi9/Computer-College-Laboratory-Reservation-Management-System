package com.college.labbooking.statistics.application;

import com.college.labbooking.audit.AuditService;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {
    private static final Set<String> APPLICANT_TYPES = Set.of("STUDENT", "TEACHER");
    private static final Set<String> STATUSES = Set.of(
            "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "IN_USE", "COMPLETED", "NO_SHOW");
    private static final String EFFECTIVE_STATUSES = "'APPROVED','IN_USE','COMPLETED'";
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public StatisticsService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public OverviewView overview(StatisticsFilter filter, CurrentUser user) {
        FilterSql sql = reservationFilter(filter, user, "r");
        return jdbcTemplate.queryForObject(
                "select count(*) total,"
                        + "count(*) filter (where r.status in (" + EFFECTIVE_STATUSES + ")) effective,"
                        + "count(*) filter (where r.status='CANCELLED') cancelled,"
                        + "count(*) filter (where r.status='NO_SHOW') no_show,"
                        + "count(*) filter (where r.applicant_type='STUDENT') student_total,"
                        + "count(*) filter (where r.applicant_type='TEACHER') teacher_total,"
                        + "count(*) filter (where r.applicant_type='STUDENT' and r.status in (" + EFFECTIVE_STATUSES + ")) student_effective,"
                        + "count(*) filter (where r.applicant_type='TEACHER' and r.status in (" + EFFECTIVE_STATUSES + ")) teacher_effective "
                        + "from reservation r" + sql.where(),
                (rs, row) -> {
                    long total = rs.getLong("total");
                    long effective = rs.getLong("effective");
                    long cancelled = rs.getLong("cancelled");
                    long noShow = rs.getLong("no_show");
                    return new OverviewView(filter.from(), filter.to(), total, effective, cancelled, noShow,
                            percent(effective, total), percent(cancelled, total), percent(noShow, total),
                            new ApplicantTypeSummary(rs.getLong("student_total"), rs.getLong("student_effective")),
                            new ApplicantTypeSummary(rs.getLong("teacher_total"), rs.getLong("teacher_effective")));
                }, sql.parameters().toArray());
    }

    public List<LabUsageView> labUsage(StatisticsFilter filter, CurrentUser user) {
        validateFilter(filter);
        StringBuilder labWhere = new StringBuilder(" where 1=1");
        List<Object> parameters = new ArrayList<>();
        parameters.add(filter.from());
        parameters.add(filter.to());
        parameters.add(filter.from());
        parameters.add(filter.to());
        if (filter.applicantType() != null) {
            parameters.add(filter.applicantType());
        }
        if (filter.labId() != null) {
            labWhere.append(" and l.id=?");
            parameters.add(filter.labId());
        }
        if (!user.hasRole("SYSTEM_ADMIN")) {
            labWhere.append(" and exists (select 1 from lab_manager lm where lm.lab_id=l.id and lm.user_id=?)");
            parameters.add(user.id());
        }
        String applicantCondition = filter.applicantType() == null ? "" : " and r.applicant_type=?";
        String query = "select l.id,l.code,l.name,"
                + "(select count(*) from generate_series(cast(? as date),cast(? as date),interval '1 day') d "
                + "cross join course_period p where p.enabled and l.status='ACTIVE' "
                + "and exists (select 1 from lab_open_rule o where o.lab_id=l.id "
                + "and o.day_of_week=extract(isodow from d)::int and o.period_no=p.period_no "
                + "and (o.valid_from is null or o.valid_from<=d::date) and (o.valid_to is null or o.valid_to>=d::date)) "
                + "and not exists (select 1 from lab_blackout b where b.lab_id=l.id "
                + "and b.booking_date=d::date and b.period_no=p.period_no)) available_slots,"
                + "(select count(*) from reservation r where r.lab_id=l.id and r.booking_date between ? and ? "
                + "and r.status in (" + EFFECTIVE_STATUSES + ")" + applicantCondition + ") occupied_slots "
                + "from lab l" + labWhere + " order by l.code";
        return jdbcTemplate.query(query, (rs, row) -> {
            long available = rs.getLong("available_slots");
            long occupied = rs.getLong("occupied_slots");
            return new LabUsageView(rs.getLong("id"), rs.getString("code"), rs.getString("name"), available,
                    occupied, percent(occupied, available));
        }, parameters.toArray());
    }

    public List<PeakHourView> peakHours(StatisticsFilter filter, CurrentUser user) {
        FilterSql sql = reservationFilter(filter, user, "r");
        return jdbcTemplate.query(
                "select p.period_no,p.name,p.start_time,p.end_time,count(r.id) total,"
                        + "count(r.id) filter (where r.status in (" + EFFECTIVE_STATUSES + ")) effective "
                        + "from course_period p left join reservation r on r.period_no=p.period_no"
                        + sql.where().replace(" where ", " and ")
                        + " group by p.period_no,p.name,p.start_time,p.end_time order by p.period_no",
                (rs, row) -> new PeakHourView(rs.getInt("period_no"), rs.getString("name"),
                        rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                        rs.getLong("total"), rs.getLong("effective")), sql.parameters().toArray());
    }

    public List<EquipmentUsageView> equipmentRanking(StatisticsFilter filter, CurrentUser user, int limit) {
        validateFilter(filter);
        StringBuilder where = new StringBuilder(
                " where r.booking_date between ? and ? and r.status in (" + EFFECTIVE_STATUSES + ")");
        List<Object> parameters = new ArrayList<>(List.of(filter.from(), filter.to()));
        addOptionalReservationFilters(filter, where, parameters, "r", false);
        addScope(user, where, parameters, "r");
        parameters.add(limit);
        return jdbcTemplate.query(
                "select e.id,e.asset_code,e.name,l.id lab_id,l.code lab_code,sum(re.quantity) quantity "
                        + "from reservation_equipment re join reservation r on r.id=re.reservation_id "
                        + "join equipment e on e.id=re.equipment_id join lab l on l.id=e.lab_id"
                        + where + " group by e.id,l.id order by quantity desc,e.id limit ?",
                (rs, row) -> new EquipmentUsageView(rs.getLong("id"), rs.getString("asset_code"),
                        rs.getString("name"), rs.getLong("lab_id"), rs.getString("lab_code"),
                        rs.getLong("quantity")), parameters.toArray());
    }

    public byte[] exportReservations(StatisticsFilter filter, CurrentUser user) {
        FilterSql sql = reservationFilter(filter, user, "r");
        Integer maxRows = jdbcTemplate.queryForObject(
                "select (setting_value #>> '{}')::int from system_setting where setting_key='export.max-rows'",
                Integer.class);
        int limit = maxRows == null ? 10000 : Math.min(maxRows, 10000);
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from reservation r" + sql.where(), Long.class, sql.parameters().toArray());
        if (count != null && count > limit) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_ROW_LIMIT_EXCEEDED",
                    "导出记录超过 " + limit + " 行，请缩小筛选范围");
        }
        List<Object> parameters = new ArrayList<>(sql.parameters());
        parameters.add(limit);
        List<ReservationExportRow> rows = jdbcTemplate.query(
                "select r.reservation_no,u.username,u.real_name,r.applicant_type,l.code lab_code,l.name lab_name,"
                        + "r.booking_date,r.period_no,r.status,r.title,r.project_or_course,r.participant_count,r.created_at "
                        + "from reservation r join sys_user u on u.id=r.applicant_id join lab l on l.id=r.lab_id"
                        + sql.where() + " order by r.booking_date,r.period_no,r.id limit ?",
                (rs, row) -> mapExportRow(rs), parameters.toArray());
        StringBuilder csv = new StringBuilder("\uFEFF预约编号,用户名,姓名,申请人类型,实验室编号,实验室名称,日期,课次,状态,标题,课程或项目,人数,创建时间\r\n");
        rows.forEach(row -> csv.append(csv(row.reservationNo())).append(',')
                .append(csv(row.username())).append(',').append(csv(row.realName())).append(',')
                .append(csv(row.applicantType())).append(',').append(csv(row.labCode())).append(',')
                .append(csv(row.labName())).append(',').append(row.bookingDate()).append(',')
                .append(row.periodNo()).append(',').append(csv(row.status())).append(',')
                .append(csv(row.title())).append(',').append(csv(row.projectOrCourse())).append(',')
                .append(row.participantCount()).append(',').append(row.createdAt()).append("\r\n"));
        auditService.record(user.id(), user.username(), "STATISTICS_EXPORTED", "RESERVATION", null, true,
                Map.of("from", filter.from().toString(), "to", filter.to().toString(), "rows", rows.size()));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private FilterSql reservationFilter(StatisticsFilter filter, CurrentUser user, String alias) {
        validateFilter(filter);
        StringBuilder where = new StringBuilder(" where " + alias + ".booking_date between ? and ?");
        List<Object> parameters = new ArrayList<>(List.of(filter.from(), filter.to()));
        addOptionalReservationFilters(filter, where, parameters, alias, true);
        addScope(user, where, parameters, alias);
        return new FilterSql(where.toString(), parameters);
    }

    private void addOptionalReservationFilters(
            StatisticsFilter filter, StringBuilder where, List<Object> parameters, String alias, boolean includeStatus) {
        if (filter.labId() != null) {
            where.append(" and ").append(alias).append(".lab_id=?");
            parameters.add(filter.labId());
        }
        if (filter.applicantType() != null) {
            where.append(" and ").append(alias).append(".applicant_type=?");
            parameters.add(filter.applicantType());
        }
        if (includeStatus && filter.status() != null) {
            where.append(" and ").append(alias).append(".status=?");
            parameters.add(filter.status());
        }
    }

    private void addScope(CurrentUser user, StringBuilder where, List<Object> parameters, String alias) {
        if (!user.hasRole("SYSTEM_ADMIN")) {
            where.append(" and exists (select 1 from lab_manager lm where lm.lab_id=")
                    .append(alias).append(".lab_id and lm.user_id=?)");
            parameters.add(user.id());
        }
    }

    private void validateFilter(StatisticsFilter filter) {
        if (filter.from() == null || filter.to() == null || filter.from().isAfter(filter.to())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "DATE_RANGE_INVALID", "统计开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(filter.from(), filter.to()) > 366) {
            throw new AppException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LARGE", "单次统计范围不能超过 366 天");
        }
        if (filter.applicantType() != null && !APPLICANT_TYPES.contains(filter.applicantType())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "APPLICANT_TYPE_INVALID", "申请人类型无效");
        }
        if (filter.status() != null && !STATUSES.contains(filter.status())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "RESERVATION_STATUS_INVALID", "预约状态无效");
        }
    }

    private ReservationExportRow mapExportRow(ResultSet rs) throws SQLException {
        return new ReservationExportRow(rs.getString("reservation_no"), rs.getString("username"),
                rs.getString("real_name"), rs.getString("applicant_type"), rs.getString("lab_code"),
                rs.getString("lab_name"), rs.getObject("booking_date", LocalDate.class), rs.getInt("period_no"),
                rs.getString("status"), rs.getString("title"), rs.getString("project_or_course"),
                rs.getInt("participant_count"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(2);
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    public record StatisticsFilter(LocalDate from, LocalDate to, Long labId, String applicantType, String status) {}

    public record ApplicantTypeSummary(long total, long effective) {}

    public record OverviewView(LocalDate from, LocalDate to, long total, long effective, long cancelled, long noShow,
            BigDecimal approvalRate, BigDecimal cancellationRate, BigDecimal noShowRate,
            ApplicantTypeSummary student, ApplicantTypeSummary teacher) {}

    public record LabUsageView(long labId, String labCode, String labName, long availableSlots, long occupiedSlots,
            BigDecimal utilizationRate) {}

    public record PeakHourView(int periodNo, String periodName, java.time.LocalTime startTime,
            java.time.LocalTime endTime, long total, long effective) {}

    public record EquipmentUsageView(long equipmentId, String assetCode, String name, long labId, String labCode,
            long quantity) {}

    private record FilterSql(String where, List<Object> parameters) {}

    private record ReservationExportRow(String reservationNo, String username, String realName, String applicantType,
            String labCode, String labName, LocalDate bookingDate, int periodNo, String status, String title,
            String projectOrCourse, int participantCount, OffsetDateTime createdAt) {}
}
