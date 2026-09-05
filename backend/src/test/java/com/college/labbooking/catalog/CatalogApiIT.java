package com.college.labbooking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogApiIT {
    private static final Database DATABASE = PostgresTestDatabase.schema("catalog_it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.flyway.default-schema", DATABASE::schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata");
    }

    @BeforeEach
    void allowBusinessApisAndCreateForeignLab() {
        jdbcTemplate.update("update sys_user set must_change_password=false, status='ACTIVE', session_version=0");
        jdbcTemplate.update("delete from lab where id=199");
        jdbcTemplate.update("insert into lab (id,code,name,building,room_no,capacity,lab_type,tags) "
                + "values (199,'FOREIGN','外部实验室','外院楼','B101',20,'通用','[]')");
    }

    @Test
    void authenticatedStudentCanReadCatalogButCannotMutateIt() throws Exception {
        String student = accessToken("student01");

        mockMvc.perform(get("/api/v1/course-periods").header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4));
        mockMvc.perform(get("/api/v1/labs?keyword=人工智能&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tags[0]").value("GPU"));
        mockMvc.perform(post("/api/v1/labs/101/blackouts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingDate\":\"2026-09-10\",\"periodNo\":1,\"reason\":\"考试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void calendarShowsAvailabilityReservationsAndBlackoutsWithoutApplicantData() throws Exception {
        jdbcTemplate.update(
                "insert into reservation (reservation_no,applicant_id,applicant_type,lab_id,title,purpose,participant_count,"
                        + "booking_date,period_no,status,contact_phone,project_or_course) values "
                        + "('CALENDAR-1',1001,'STUDENT',101,'私密标题','私密用途',10,'2030-01-07',1,'APPROVED','13800000001','测试')");
        jdbcTemplate.update(
                "insert into lab_blackout(lab_id,booking_date,period_no,reason,created_by) values "
                        + "(101,'2030-01-07',2,'设备维护',1003)");

        String student = accessToken("student01");
        mockMvc.perform(get("/api/v1/labs/101/calendar?from=2030-01-07&to=2030-01-07")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].slotStatus").value("RESERVED"))
                .andExpect(jsonPath("$.data[1].slotStatus").value("BLACKOUT"))
                .andExpect(jsonPath("$.data[1].reason").value("设备维护"))
                .andExpect(jsonPath("$.data[2].slotStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].applicantName").doesNotExist())
                .andExpect(jsonPath("$.data[0].title").doesNotExist());

        mockMvc.perform(get("/api/v1/labs/101/calendar?from=2030-01-01&to=2030-03-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATE_RANGE_INVALID"));
    }

    @Test
    void labAdministratorCanMaintainOnlyAssignedLabResources() throws Exception {
        String admin = accessToken("labadmin01");

        MvcResult rules = mockMvc.perform(put("/api/v1/labs/101/open-rules")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"dayOfWeek\":3,\"periodNo\":2,\"validFrom\":\"2026-09-01\",\"validTo\":\"2026-12-31\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(rules.getResponse().getContentAsByteArray()).path("data").get(0)
                        .path("dayOfWeek").asInt())
                .isEqualTo(3);

        MvcResult blackout = mockMvc.perform(post("/api/v1/labs/101/blackouts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingDate\":\"2026-09-10\",\"periodNo\":2,\"reason\":\"维护\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("维护"))
                .andReturn();
        long blackoutId = objectMapper.readTree(blackout.getResponse().getContentAsByteArray())
                .path("data").path("id").asLong();
        mockMvc.perform(delete("/api/v1/labs/101/blackouts/{id}", blackoutId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"labId":101,"assetCode":"CAM-A301","category":"采集设备","name":"摄像机",
                                 "model":"4K","totalQuantity":3,"status":"AVAILABLE","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labId").value(101));

        mockMvc.perform(put("/api/v1/labs/199/open-rules")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"dayOfWeek\":1,\"periodNo\":1}]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LAB_SCOPE_DENIED"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from audit_log where action like 'LAB_%' or action like 'EQUIPMENT_%'",
                        Integer.class))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void optimisticVersionPreventsLostUpdates() throws Exception {
        String admin = accessToken("labadmin01");
        String request = """
                {"code":"LAB-A301","name":"人工智能实验室","building":"计算机楼","roomNo":"A301",
                 "capacity":61,"labType":"专业实验室","description":"更新","tags":["GPU"],"status":"ACTIVE",
                 "studentApprovalMode":"MANUAL","teacherApprovalMode":"AUTO","allowStudentBooking":true,
                 "maxPeriodsPerUserDay":2,"advanceDays":14,"cancelBeforeMinutes":120,"requireCheckIn":true,"version":0}
                """;
        mockMvc.perform(put("/api/v1/labs/101")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(put("/api/v1/labs/101")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"));
    }

    @Test
    void systemAdministratorCanConfigurePeriodsAndCreateLabs() throws Exception {
        String admin = accessToken("sysadmin01");
        mockMvc.perform(put("/api/v1/course-periods/4")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"晚间大课\",\"startTime\":\"18:30\",\"endTime\":\"20:10\",\"enabled\":true,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("晚间大课"));
        mockMvc.perform(post("/api/v1/labs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LAB-C201","name":"网络实验室","building":"计算机楼","roomNo":"C201",
                                 "capacity":36,"labType":"专业实验室","tags":["网络"],"status":"ACTIVE",
                                 "studentApprovalMode":"MANUAL","teacherApprovalMode":"MANUAL","allowStudentBooking":true,
                                 "maxPeriodsPerUserDay":2,"advanceDays":14,"cancelBeforeMinutes":120,"requireCheckIn":true,
                                 "responsibleUserId":1003,"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("LAB-C201"))
                .andExpect(jsonPath("$.data.responsibleUserId").value(1003))
                .andExpect(jsonPath("$.data.responsibleUserName").value("实验室管理员"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from lab_manager lm join lab l on l.id=lm.lab_id "
                                + "where l.code='LAB-C201' and lm.user_id=1003",
                        Integer.class))
                .isOne();
    }

    private String accessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return root.path("data").path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
