package com.college.labbooking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.reservation.application.ReservationService;
import com.college.labbooking.reservation.application.ReservationService.ReservationView;
import com.college.labbooking.reservation.web.ReservationController.CreateReservationRequest;
import com.college.labbooking.reservation.web.ReservationController.EquipmentItemRequest;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
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
class ReservationApiIT {
    private static final Database DATABASE = PostgresTestDatabase.schema("reservation_it");
    private static final CurrentUser STUDENT = new CurrentUser(1001, "student01", Set.of("STUDENT"), Set.of(), false);
    private static final CurrentUser TEACHER = new CurrentUser(1002, "teacher01", Set.of("TEACHER"), Set.of(), false);
    private static final CurrentUser LAB_ADMIN = new CurrentUser(1003, "labadmin01", Set.of("LAB_ADMIN"), Set.of(), false);
    private static final CurrentUser SYSTEM_ADMIN = new CurrentUser(1004, "sysadmin01", Set.of("SYSTEM_ADMIN"), Set.of(), false);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationService reservationService;

    private ExecutorService executor;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.flyway.default-schema", DATABASE::schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata");
    }

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.update("delete from outbox_delivery_attempt");
        jdbcTemplate.update("delete from outbox_event");
        jdbcTemplate.update("delete from notification");
        jdbcTemplate.update("delete from approval_record");
        jdbcTemplate.update("delete from reservation_status_history");
        jdbcTemplate.update("delete from attendance_record");
        jdbcTemplate.update("delete from reservation_equipment");
        jdbcTemplate.update("delete from reservation");
        jdbcTemplate.update("delete from idempotency_record");
        jdbcTemplate.update("delete from refresh_session");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from lab_blackout");
        jdbcTemplate.update("delete from lab where id=199");
        jdbcTemplate.update("update sys_user set must_change_password=false,status='ACTIVE',session_version=0,booking_frozen_until=null");
        jdbcTemplate.update("update lab set status='ACTIVE',allow_student_booking=true,max_periods_per_user_day=2,"
                + "advance_days=14,cancel_before_minutes=120,student_approval_mode='MANUAL',version=0");
        jdbcTemplate.update("update lab set teacher_approval_mode='AUTO' where id=101");
        jdbcTemplate.update("update lab set teacher_approval_mode='MANUAL' where id=102");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void availabilityHonorsCapacityOpenRulesBlackoutsAndExistingEffectiveReservations() throws Exception {
        LocalDate date = nextMonday();
        String student = accessToken("student01");
        mockMvc.perform(get("/api/v1/availability/labs")
                        .param("bookingDate", date.toString()).param("periodNo", "1").param("capacity", "55")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(101));

        jdbcTemplate.update("insert into lab_blackout (lab_id,booking_date,period_no,reason,created_by) values (101,?,?,?,1003)",
                date, 1, "考试");
        mockMvc.perform(get("/api/v1/availability/labs")
                        .param("bookingDate", date.toString()).param("periodNo", "1").param("capacity", "55")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void applicantTypeComesFromServerAndCreateIsIdempotent() throws Exception {
        LocalDate date = nextMonday();
        String teacher = accessToken("teacher01");
        String payload = createJson(101, date, 1, List.of());
        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(teacher))
                        .header("X-Idempotency-Key", "teacher-auto-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicantType").value("TEACHER"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andReturn();
        long id = json(first).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(teacher))
                        .header("X-Idempotency-Key", "teacher-auto-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbcTemplate.queryForObject("select count(*) from reservation", Integer.class)).isEqualTo(1);

        String systemAdmin = accessToken("sysadmin01");
        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(systemAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(102, date, 2, List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_IDENTITY_REQUIRED"));
    }

    @Test
    void bookingRulesRejectQualificationCapacityAndBlackoutViolations() {
        LocalDate date = nextMonday();
        assertThat(captureCode(() -> reservationService.create(
                request(101, date, 1, 61, List.of()), null, STUDENT))).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(captureCode(() -> reservationService.create(
                request(101, date, 1, 20, List.of(new EquipmentItemRequest(201, 1))), null, STUDENT)))
                .isEqualTo("EQUIPMENT_QUALIFICATION_REQUIRED");
        jdbcTemplate.update("insert into lab_blackout (lab_id,booking_date,period_no,reason,created_by) values (101,?,?,?,1003)",
                date, 1, "维护");
        assertThat(captureCode(() -> reservationService.create(
                request(101, date, 1, 20, List.of()), null, STUDENT))).isEqualTo("LAB_BLACKOUT");
    }

    @Test
    void concurrentApprovalAllowsOnlyOneEffectiveReservationForTheSameSlot() throws Exception {
        LocalDate date = nextMonday();
        ReservationView first = reservationService.create(request(102, date, 1, 12, List.of()), "slot-a", STUDENT);
        ReservationView second = reservationService.create(request(102, date, 1, 12, List.of()), "slot-b", TEACHER);

        List<String> outcomes = concurrent(
                () -> reservationService.approve(first.id(), first.version(), "同意", "approve-a", LAB_ADMIN),
                () -> reservationService.approve(second.id(), second.version(), "同意", "approve-b", LAB_ADMIN));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "LAB_SLOT_CONFLICT");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from reservation where lab_id=102 and booking_date=? and period_no=1 "
                                + "and status in ('APPROVED','IN_USE','COMPLETED')", Integer.class, date))
                .isEqualTo(1);
    }

    @Test
    void equipmentRequestsCannotExceedInventoryDuringApproval() throws Exception {
        LocalDate date = nextMonday();
        jdbcTemplate.update("update lab set teacher_approval_mode='MANUAL' where id=101");
        EquipmentItemRequest sixHeadsets = new EquipmentItemRequest(202, 6);
        ReservationView first = reservationService.create(request(101, date, 2, 12, List.of(sixHeadsets)), "equip-a", STUDENT);
        ReservationView second = reservationService.create(request(101, date, 2, 12, List.of(sixHeadsets)), "equip-b", TEACHER);
        List<String> outcomes = concurrent(
                () -> reservationService.approve(first.id(), first.version(), "同意", "equip-approve-a", LAB_ADMIN),
                () -> reservationService.approve(second.id(), second.version(), "同意", "equip-approve-b", LAB_ADMIN));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "LAB_SLOT_CONFLICT");
        Integer allocated = jdbcTemplate.queryForObject(
                "select coalesce(sum(re.quantity),0) from reservation_equipment re join reservation r on r.id=re.reservation_id "
                        + "where re.equipment_id=202 and r.booking_date=? and r.period_no=2 and r.status='APPROVED'",
                Integer.class, date);
        assertThat(allocated).isLessThanOrEqualTo(10);

        ReservationView excessive = reservationService.create(
                request(101, date, 3, 12, List.of(new EquipmentItemRequest(202, 11))), "equip-too-many", STUDENT);
        assertThat(captureCode(() -> reservationService.approve(
                excessive.id(), excessive.version(), "同意", "equip-approve-too-many", LAB_ADMIN)))
                .isEqualTo("EQUIPMENT_CAPACITY_EXCEEDED");
    }

    @Test
    void decisionsEnforceVersionStateIdempotencyAndDataScope() {
        LocalDate date = nextMonday();
        ReservationView pending = reservationService.create(request(102, date, 4, 10, List.of()), "decision", STUDENT);
        assertThat(captureCode(() -> reservationService.reject(
                pending.id(), 9, "资料不足", "reject-stale", LAB_ADMIN))).isEqualTo("RESOURCE_VERSION_CONFLICT");
        ReservationView rejected = reservationService.reject(pending.id(), 0, "资料不足", "reject-once", LAB_ADMIN);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(reservationService.reject(pending.id(), 0, "资料不足", "reject-once", LAB_ADMIN).id())
                .isEqualTo(pending.id());
        assertThat(captureCode(() -> reservationService.reject(
                pending.id(), 0, "另一个原因", "reject-once", LAB_ADMIN))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(captureCode(() -> reservationService.approve(
                pending.id(), 1, "再批准", "approve-rejected", LAB_ADMIN)))
                .isEqualTo("ILLEGAL_RESERVATION_TRANSITION");

        jdbcTemplate.update("insert into lab (id,code,name,building,room_no,capacity,lab_type,tags) "
                + "values (199,'FOREIGN-RES','外部实验室','外院楼','B201',20,'通用','[]')");
        jdbcTemplate.update("insert into lab_open_rule (lab_id,day_of_week,period_no) values (199,?,1)",
                date.getDayOfWeek().getValue());
        ReservationView foreign = reservationService.create(request(199, date, 1, 5, List.of()), null, STUDENT);
        assertThat(captureCode(() -> reservationService.detail(foreign.id(), LAB_ADMIN)))
                .isEqualTo("RESERVATION_SCOPE_DENIED");
        assertThat(reservationService.detail(foreign.id(), SYSTEM_ADMIN).id()).isEqualTo(foreign.id());
    }

    @Test
    void applicantCanListInspectAndCancelWhileManagerCanSeeTheResult() throws Exception {
        LocalDate date = nextMonday();
        ReservationView pending = reservationService.create(request(102, date, 2, 8, List.of()), "self-flow", STUDENT);
        String student = accessToken("student01");
        mockMvc.perform(get("/api/v1/reservations/my")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(pending.id()));
        mockMvc.perform(get("/api/v1/reservations/{id}", pending.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextAction").value("WAITING_APPROVAL"));
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", pending.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"课程安排调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        String manager = accessToken("labadmin01");
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("labId", "102").param("status", "CANCELLED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/admin/reservations/{id}/history", pending.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private List<String> concurrent(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> one = executor.submit(() -> invokeTogether(first, ready, start));
        Future<String> two = executor.submit(() -> invokeTogether(second, ready, start));
        ready.await();
        start.countDown();
        return List.of(one.get(), two.get());
    }

    private String invokeTogether(ThrowingSupplier supplier, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            supplier.get();
            return "OK";
        } catch (AppException exception) {
            return exception.code();
        }
    }

    private String captureCode(ThrowingSupplier supplier) {
        try {
            supplier.get();
            return "OK";
        } catch (AppException exception) {
            return exception.code();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private CreateReservationRequest request(
            long labId, LocalDate date, int periodNo, int participants, List<EquipmentItemRequest> equipment) {
        return new CreateReservationRequest(labId, "课程实验", "完成课程实验任务", participants, date, periodNo,
                "软件工程", "13800000000", equipment, "测试预约");
    }

    private String createJson(long labId, LocalDate date, int periodNo, List<EquipmentItemRequest> equipment) throws Exception {
        return objectMapper.writeValueAsString(request(labId, date, periodNo, 20, equipment));
    }

    private LocalDate nextMonday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private String accessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("accessToken").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        ReservationView get() throws Exception;
    }
}
