package com.college.labbooking.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdministrationStatisticsApiIT {
    private static final Database DATABASE = PostgresTestDatabase.schema("administration_statistics_it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.flyway.default-schema", DATABASE::schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata");
    }

    @BeforeEach
    void unlockDemoUsers() {
        jdbcTemplate.update("update sys_user set must_change_password=false,status='ACTIVE',session_version=0,version=0");
    }

    @Test
    void systemAdministratorCreatesListsUpdatesAndRevokesAUser() throws Exception {
        String administrator = accessToken("sysadmin01", "ChangeMe123!");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student99","realName":"新建学生","userType":"STUDENT",
                                 "department":"计算机学院","email":"student99@example.invalid","phone":"13800000099",
                                 "status":"ACTIVE","roles":["STUDENT"],"initialPassword":"InitialPass99!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        long userId = json(created).path("data").path("id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                        "select password_hash from sys_user where id=?", String.class, userId))
                .startsWith("$2").doesNotContain("InitialPass99!");

        mockMvc.perform(get("/api/v1/admin/users?keyword=student99")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        jdbcTemplate.update("update sys_user set must_change_password=false where id=?", userId);
        entityManager.clear();
        String studentToken = accessToken("student99", "InitialPass99!");
        mockMvc.perform(put("/api/v1/admin/users/{id}", userId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student99","realName":"新建学生","userType":"STUDENT",
                                 "department":"计算机学院","email":"student99@example.invalid","phone":"13800000099",
                                 "status":"DISABLED","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        entityManager.clear();
        mockMvc.perform(get("/api/v1/labs").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from audit_log where target_id=? and action in ('USER_CREATED','USER_UPDATED')",
                        Integer.class, Long.toString(userId)))
                .isEqualTo(2);
    }

    @Test
    void roleManagementUsesVersionsRevokesMembersAndProtectsBuiltInAdministrator() throws Exception {
        String administrator = accessToken("sysadmin01", "ChangeMe123!");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/roles")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"REPORT_VIEWER","name":"报表查看员","description":"只读统计",
                                 "permissions":["statistics:read"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("REPORT_VIEWER"))
                .andReturn();
        long roleId = json(created).path("data").path("id").asLong();
        MvcResult staffCreated = mockMvc.perform(post("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"reporter01","realName":"报表用户","userType":"STAFF",
                                 "department":"实验中心","status":"ACTIVE","roles":["REPORT_VIEWER"],
                                 "initialPassword":"ReporterPass99!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long staffId = json(staffCreated).path("data").path("id").asLong();
        jdbcTemplate.update("update sys_user set must_change_password=false where id=?", staffId);
        entityManager.clear();
        String staffToken = accessToken("reporter01", "ReporterPass99!");
        mockMvc.perform(put("/api/v1/admin/users/{id}/roles", staffId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"REPORT_VIEWER\",\"LAB_ADMIN\"],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.version").value(1));
        entityManager.clear();
        mockMvc.perform(get("/api/v1/statistics/overview?from=2026-09-07&to=2026-09-07")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/v1/admin/roles/{id}", roleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"报表与目录查看员","description":"更新后权限","permissions":["statistics:read","lab:read"],"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.permissions.length()").value(2));
        mockMvc.perform(put("/api/v1/admin/roles/{id}", roleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"过期页面","permissions":[],"version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"));

        mockMvc.perform(put("/api/v1/admin/users/1004")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"sysadmin01","realName":"系统管理员","userType":"STAFF",
                                 "department":"信息化办公室","email":"sysadmin01@example.invalid","phone":"13800000004",
                                 "status":"DISABLED","version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELF_DISABLE_NOT_ALLOWED"));
    }

    @Test
    void csvUserImportReturnsTraceableTaskAndKeepsRowFailures() throws Exception {
        String administrator = accessToken("sysadmin01", "ChangeMe123!");
        String csv = "username,realName,userType,department,email,phone,roles,initialPassword\n"
                + "teacher99,导入教师,TEACHER,计算机学院,teacher99@example.invalid,13800000098,TEACHER,ImportedPass99!\n"
                + "bad-user,错误用户,STUDENT,计算机学院,,,MISSING_ROLE,ImportedPass98!\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "users.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MvcResult imported = mockMvc.perform(multipart("/api/v1/admin/users/import")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.succeededRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andReturn();
        String taskId = json(imported).path("data").path("id").asText();
        mockMvc.perform(get("/api/v1/admin/import-tasks/{id}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorSummary").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                        "select password_hash from sys_user where username='teacher99'", String.class))
                .startsWith("$2").doesNotContain("ImportedPass99!");
    }

    @Test
    void settingsAndAuditLogAreVersionedAuditedAndSystemAdministratorOnly() throws Exception {
        String administrator = accessToken("sysadmin01", "ChangeMe123!");
        String labAdministrator = accessToken("labadmin01", "ChangeMe123!");
        mockMvc.perform(put("/api/v1/admin/settings/violation.no-show-threshold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":4,\"description\":\"四次爽约冻结\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(4))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(put("/api/v1/admin/settings/violation.no-show-threshold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":5,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"));
        mockMvc.perform(get("/api/v1/admin/audit-logs?action=SYSTEM_SETTING_UPDATED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].targetId").value("violation.no-show-threshold"));
        mockMvc.perform(get("/api/v1/admin/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(labAdministrator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void statisticsReconcileWithDetailsRespectScopeAndExportIdenticalFilters() throws Exception {
        seedStatisticsData();
        String administrator = accessToken("sysadmin01", "ChangeMe123!");
        String labAdministrator = accessToken("labadmin01", "ChangeMe123!");
        String student = accessToken("student01", "ChangeMe123!");
        String filter = "?from=2026-09-07&to=2026-09-07&labId=101";

        MvcResult overview = mockMvc.perform(get("/api/v1/statistics/overview" + filter)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.effective").value(2))
                .andExpect(jsonPath("$.data.cancelled").value(1))
                .andExpect(jsonPath("$.data.approvalRate").value(66.67))
                .andExpect(jsonPath("$.data.student.total").value(2))
                .andExpect(jsonPath("$.data.teacher.total").value(1))
                .andReturn();
        assertThat(json(overview).path("data").path("total").asLong()).isEqualTo(jdbcTemplate.queryForObject(
                "select count(*) from reservation where booking_date='2026-09-07' and lab_id=101", Long.class));

        mockMvc.perform(get("/api/v1/statistics/lab-usage" + filter)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].availableSlots").value(4))
                .andExpect(jsonPath("$.data[0].occupiedSlots").value(2))
                .andExpect(jsonPath("$.data[0].utilizationRate").value(50.00));
        mockMvc.perform(get("/api/v1/statistics/peak-hours" + filter)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].total").value(1))
                .andExpect(jsonPath("$.data[1].effective").value(1));
        mockMvc.perform(get("/api/v1/statistics/equipment-ranking" + filter)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].equipmentId").value(201))
                .andExpect(jsonPath("$.data[0].quantity").value(3));

        MvcResult exported = mockMvc.perform(get("/api/v1/statistics/export.csv" + filter + "&status=CANCELLED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reservation-report.csv"))
                .andReturn();
        String csv = exported.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("STAT-CANCELLED").doesNotContain("STAT-APPROVED").contains("'=SUM(1,1)");
        assertThat(csv.lines()).hasSize(2);

        mockMvc.perform(get("/api/v1/statistics/overview?from=2026-09-07&to=2026-09-07")
                        .header(HttpHeaders.AUTHORIZATION, bearer(labAdministrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5));
        mockMvc.perform(get("/api/v1/statistics/overview?from=2026-09-07&to=2026-09-07")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6));
        mockMvc.perform(get("/api/v1/statistics/overview" + filter)
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isForbidden());
    }

    private void seedStatisticsData() {
        jdbcTemplate.update("insert into lab (id,code,name,building,room_no,capacity,lab_type,tags) "
                + "values (199,'STAT-FOREIGN','统计外部实验室','外院楼','S199',30,'通用','[]')");
        jdbcTemplate.update("insert into lab_open_rule (lab_id,day_of_week,period_no) "
                + "select 199,1,generate_series(1,4)");
        insertReservation(8101, "STAT-APPROVED", 1001, "STUDENT", 101, 1, "APPROVED", "有效学生预约");
        insertReservation(8102, "STAT-COMPLETED", 1002, "TEACHER", 101, 2, "COMPLETED", "有效教师预约");
        insertReservation(8103, "STAT-CANCELLED", 1001, "STUDENT", 101, 3, "CANCELLED", "=SUM(1,1)");
        insertReservation(8104, "STAT-NO-SHOW", 1002, "TEACHER", 102, 1, "NO_SHOW", "爽约");
        insertReservation(8105, "STAT-REJECTED", 1001, "STUDENT", 102, 2, "REJECTED", "驳回");
        insertReservation(8106, "STAT-FOREIGN-ONE", 1001, "STUDENT", 199, 1, "APPROVED", "范围外");
        jdbcTemplate.update("insert into reservation_equipment (reservation_id,equipment_id,quantity) values (8102,201,3)");
    }

    private void insertReservation(long id, String number, long applicantId, String applicantType, long labId,
            int periodNo, String status, String title) {
        jdbcTemplate.update("insert into reservation (id,reservation_no,applicant_id,applicant_type,lab_id,title,purpose,"
                        + "participant_count,booking_date,period_no,status,contact_phone,project_or_course) "
                        + "values (?,?,?,?,?,?,'统计核对',10,'2026-09-07',?,?, '13800000000','统计测试')",
                id, number, applicantId, applicantType, labId, title, periodNo, status);
    }

    private String accessToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("accessToken").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
