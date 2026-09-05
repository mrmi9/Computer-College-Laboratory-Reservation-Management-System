package com.college.labbooking.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.labbooking.attendance.application.AttendanceService;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.notification.application.NotificationChannel;
import com.college.labbooking.notification.application.NotificationService;
import com.college.labbooking.notification.application.OutboxDispatcher;
import com.college.labbooking.reservation.application.ReservationService.ReservationView;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AttendanceOutboxIT {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Database DATABASE = PostgresTestDatabase.schema("attendance_it");
    private static final CurrentUser STUDENT = new CurrentUser(1001, "student01", Set.of("STUDENT"), Set.of(), false);
    private static final CurrentUser TEACHER = new CurrentUser(1002, "teacher01", Set.of("TEACHER"), Set.of(), false);
    private static final CurrentUser LAB_ADMIN = new CurrentUser(1003, "labadmin01", Set.of("LAB_ADMIN"), Set.of(), false);

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private OutboxDispatcher outboxDispatcher;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ExecutorService executor;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.flyway.default-schema", DATABASE::schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata");
        registry.add("app.jobs.attendance-delay-ms", () -> "3600000");
        registry.add("app.jobs.outbox-delay-ms", () -> "3600000");
    }

    @BeforeEach
    void resetDataAndAlignPeriodWithNow() {
        jdbcTemplate.update("delete from outbox_delivery_attempt");
        jdbcTemplate.update("delete from outbox_event");
        jdbcTemplate.update("delete from notification");
        jdbcTemplate.update("delete from user_violation");
        jdbcTemplate.update("delete from approval_record");
        jdbcTemplate.update("delete from reservation_status_history");
        jdbcTemplate.update("delete from attendance_record");
        jdbcTemplate.update("delete from reservation_equipment");
        jdbcTemplate.update("delete from reservation");
        jdbcTemplate.update("delete from refresh_session");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("update sys_user set must_change_password=false,status='ACTIVE',session_version=0,"
                + "no_show_count=0,booking_frozen_until=null");
        jdbcTemplate.update("update lab set require_check_in=true where id in (101,102)");
        LocalTime localNow = LocalTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MINUTES);
        LocalTime start = localNow.minusMinutes(1);
        LocalTime end = localNow.plusMinutes(60);
        if (end.isAfter(start)) {
            jdbcTemplate.update("update course_period set start_time=?,end_time=? where period_no=4", start, end);
        }
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void applicantAndAdministratorCanCheckInAndCheckOutWithAudit() throws Exception {
        Instant now = Instant.now();
        long own = insertReservation(1001, 101, "APPROVED", today(), 4);
        assertThat(attendanceService.checkInAt(own, 18, "按时到场", STUDENT, false, now).status())
                .isEqualTo("IN_USE");
        assertThat(attendanceService.checkOut(own, 17, "正常结束", STUDENT, false).status())
                .isEqualTo("COMPLETED");

        long managed = insertReservation(1002, 102, "APPROVED", today(), 4);
        assertThat(code(() -> attendanceService.checkInAt(managed, 10, null, STUDENT, false, now)))
                .isEqualTo("RESERVATION_SCOPE_DENIED");
        assertThat(attendanceService.checkInAt(managed, 10, "现场登记", LAB_ADMIN, true, now).checkInMethod())
                .isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from audit_log where action in ('CHECK_IN','CHECK_OUT','ADMIN_CHECK_IN')",
                        Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentNoShowSweepAndManualCheckInProduceOneValidFinalState() throws Exception {
        LocalDate date = today();
        LocalTime startTime = jdbcTemplate.queryForObject(
                "select start_time from course_period where period_no=4", (rs, row) -> rs.getTime(1).toLocalTime());
        Instant boundary = LocalDateTime.of(date, startTime).atZone(BUSINESS_ZONE).plusMinutes(15).toInstant();
        long reservationId = insertReservation(1001, 101, "APPROVED", date, 4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> checkIn = executor.submit(() -> together(ready, start,
                () -> attendanceService.checkInAt(reservationId, 12, null, STUDENT, false, boundary)));
        Future<String> noShow = executor.submit(() -> together(ready, start, () -> {
            attendanceService.processNoShows(boundary);
        }));
        ready.await();
        start.countDown();
        List<String> outcomes = List.of(checkIn.get(), noShow.get());

        String state = jdbcTemplate.queryForObject("select status from reservation where id=?", String.class, reservationId);
        assertThat(state).isIn("IN_USE", "NO_SHOW");
        assertThat(outcomes).contains("OK");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from reservation_status_history where reservation_id=? and to_status in ('IN_USE','NO_SHOW')",
                        Integer.class, reservationId))
                .isEqualTo(1);
    }

    @Test
    void noShowCreatesViolationNotificationAndFreezesAtThreshold() {
        jdbcTemplate.update("update sys_user set no_show_count=2 where id=1001");
        long reservationId = insertReservation(1001, 101, "APPROVED", today().minusDays(1), 1);
        assertThat(attendanceService.processNoShows(Instant.now())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from reservation where id=?", String.class, reservationId))
                .isEqualTo("NO_SHOW");
        assertThat(jdbcTemplate.queryForObject("select no_show_count from sys_user where id=1001", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "select booking_frozen_until is not null from sys_user where id=1001", Boolean.class))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_violation", Integer.class)).isEqualTo(1);
        assertThat(notificationService.list(STUDENT, true, 0, 20).total()).isEqualTo(1);
    }

    @Test
    void schedulerCompletesCheckedInAndNoCheckInReservationsAfterEnd() {
        long inUse = insertReservation(1001, 101, "IN_USE", today().minusDays(1), 1);
        jdbcTemplate.update("insert into attendance_record (reservation_id,check_in_at,check_in_method,operated_by) "
                + "values (?,now()-interval '2 hours','WEB',1001)", inUse);
        jdbcTemplate.update("update lab set require_check_in=false where id=102");
        long noCheckIn = insertReservation(1002, 102, "APPROVED", today().minusDays(1), 1);

        assertThat(attendanceService.processAutoCompletion(Instant.now())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from reservation where id in (?,?) and status='COMPLETED'",
                        Integer.class, inUse, noCheckIn))
                .isEqualTo(2);
    }

    @Test
    void outboxRetriesWithAttemptsAndStopsAfterPermanentFailure() {
        UUID failedId = insertOutbox("FORCED_FAILURE");
        NotificationChannel failing = new NotificationChannel() {
            @Override
            public String name() {
                return "TEST";
            }

            @Override
            public void deliver(OutboxDispatcher.OutboxMessage message) {
                throw new IllegalStateException("simulated downstream outage");
            }
        };
        Instant base = Instant.now();
        for (int attempt = 0; attempt < 5; attempt++) {
            outboxDispatcher.dispatchBatch(base.plus(attempt + 1L, ChronoUnit.HOURS), failing);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "select status || ':' || retry_count from outbox_event where id=?", String.class, failedId))
                .isEqualTo("FAILED:5");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from outbox_delivery_attempt where event_id=?", Integer.class, failedId))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                        "select outcome from outbox_delivery_attempt where event_id=? order by attempt_no desc limit 1",
                        String.class, failedId))
                .isEqualTo("FAILED");
        assertThat(outboxDispatcher.dispatchBatch(base.plus(24, ChronoUnit.HOURS), failing)).isZero();

        UUID deliveredId = insertOutbox("DELIVER_ME");
        NotificationChannel success = new NotificationChannel() {
            public String name() { return "TEST"; }
            public void deliver(OutboxDispatcher.OutboxMessage message) {}
        };
        assertThat(outboxDispatcher.dispatchBatch(base.plus(25, ChronoUnit.HOURS), success)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from outbox_event where id=?", String.class, deliveredId))
                .isEqualTo("DELIVERED");
    }

    @Test
    void notificationApiEnforcesRecipientAndSupportsReadOperations() throws Exception {
        jdbcTemplate.update("insert into notification (recipient_id,notification_type,title,content) "
                + "values (1001,'TEST','测试通知','内容'),(1002,'TEST','他人通知','内容')");
        String token = accessToken("student01");
        MvcResult list = mockMvc.perform(get("/api/v1/notifications?unreadOnly=true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();
        long id = objectMapper.readTree(list.getResponse().getContentAsByteArray())
                .path("data").path("items").get(0).path("id").asLong();
        mockMvc.perform(put("/api/v1/notifications/{id}/read", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").exists());
        mockMvc.perform(put("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        assertThat(code(() -> notificationService.markRead(
                jdbcTemplate.queryForObject("select id from notification where recipient_id=1002", Long.class), STUDENT)))
                .isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    private long insertReservation(long applicantId, long labId, String status, LocalDate date, int periodNo) {
        String type = applicantId == 1002 ? "TEACHER" : "STUDENT";
        return jdbcTemplate.queryForObject(
                "insert into reservation (reservation_no,applicant_id,applicant_type,lab_id,title,purpose,participant_count,"
                        + "booking_date,period_no,status,contact_phone,project_or_course) values (?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, "ATT-" + UUID.randomUUID().toString().substring(0, 10), applicantId, type, labId,
                "签到测试", "签到测试", 12, date, periodNo, status, "13800000000", "测试课程");
    }

    private UUID insertOutbox(String eventType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into outbox_event (id,aggregate_type,aggregate_id,event_type,payload) "
                + "values (?,'TEST','1',?,cast('{\"value\":1}' as jsonb))", id, eventType);
        return id;
    }

    private String together(CountDownLatch ready, CountDownLatch start, CheckedOperation operation) throws Exception {
        ready.countDown();
        start.await();
        try {
            operation.run();
            return "OK";
        } catch (AppException exception) {
            return exception.code();
        }
    }

    private String code(CheckedOperation operation) {
        try {
            operation.run();
            return "OK";
        } catch (AppException exception) {
            return exception.code();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    private String accessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
