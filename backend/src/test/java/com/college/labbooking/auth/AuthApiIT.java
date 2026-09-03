package com.college.labbooking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.labbooking.auth.web.AuthController;
import com.college.labbooking.security.CurrentUser;
import com.college.labbooking.security.DataScopeService;
import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
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
class AuthApiIT {
    private static final String INITIAL_HASH = "$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa";
    private static final Database DATABASE = PostgresTestDatabase.schema("auth_it");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataScopeService dataScopeService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.flyway.default-schema", DATABASE::schema);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata");
    }

    @BeforeEach
    void resetUsersAndSessions() {
        jdbcTemplate.update("delete from refresh_session");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update(
                "update sys_user set password_hash = ?, status = 'ACTIVE', must_change_password = true, "
                        + "failed_login_count = 0, locked_until = null, session_version = 0, version = 0",
                INITIAL_HASH);
    }

    @Test
    void loginIssuesAccessTokenAndStoresOnlyRefreshDigest() throws Exception {
        MvcResult result = login("student01", "ChangeMe123!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.data.user.roles[0]").value("STUDENT"))
                .andReturn();

        String refreshToken = cookieValue(result, AuthController.REFRESH_COOKIE);
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                        .filter(value -> value.startsWith(AuthController.REFRESH_COOKIE + "="))
                        .findFirst()
                        .orElseThrow())
                .contains("HttpOnly", "SameSite=Strict");
        String stored = jdbcTemplate.queryForObject("select token_hash from refresh_session", String.class);
        assertThat(stored).hasSize(64).doesNotContain(refreshToken);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from audit_log where action = 'LOGIN_SUCCESS'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void fifthInvalidPasswordLocksAccount() throws Exception {
        for (int attempt = 1; attempt < 5; attempt++) {
            login("student01", "wrong-password").andExpect(status().isUnauthorized());
        }
        login("student01", "wrong-password")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        login("student01", "ChangeMe123!").andExpect(status().isLocked());

        assertThat(jdbcTemplate.queryForObject(
                        "select failed_login_count from sys_user where username = 'student01'", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesTheSessionFamily() throws Exception {
        MvcResult login = login("teacher01", "ChangeMe123!").andExpect(status().isOk()).andReturn();
        String oldRefresh = cookieValue(login, AuthController.REFRESH_COOKIE);
        String oldCsrf = cookieValue(login, AuthController.CSRF_COOKIE);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, oldRefresh))
                        .cookie(new Cookie(AuthController.CSRF_COOKIE, oldCsrf))
                        .header("X-CSRF-TOKEN", oldCsrf))
                .andExpect(status().isOk())
                .andReturn();
        String newRefresh = cookieValue(refreshed, AuthController.REFRESH_COOKIE);
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from refresh_session where revoked_at is null", Integer.class))
                .isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, oldRefresh))
                        .cookie(new Cookie(AuthController.CSRF_COOKIE, oldCsrf))
                        .header("X-CSRF-TOKEN", oldCsrf))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from refresh_session where revoked_at is null", Integer.class))
                .isZero();
    }

    @Test
    void refreshRequiresDoubleSubmitCsrfToken() throws Exception {
        MvcResult login = login("student01", "ChangeMe123!").andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(
                                AuthController.REFRESH_COOKIE,
                                cookieValue(login, AuthController.REFRESH_COOKIE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void logoutRevokesTheRefreshSessionAndClearsCookies() throws Exception {
        MvcResult login = login("teacher01", "ChangeMe123!").andExpect(status().isOk()).andReturn();
        String accessToken = json(login).path("data").path("accessToken").asText();
        String refreshToken = cookieValue(login, AuthController.REFRESH_COOKIE);
        String csrfToken = cookieValue(login, AuthController.CSRF_COOKIE);

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, refreshToken)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(logout.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(value -> value.startsWith(AuthController.REFRESH_COOKIE + "=;") && value.contains("Max-Age=0"))
                .anyMatch(value -> value.startsWith(AuthController.CSRF_COOKIE + "=;") && value.contains("Max-Age=0"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from refresh_session where revoked_at is null", Integer.class))
                .isZero();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE, refreshToken))
                        .cookie(new Cookie(AuthController.CSRF_COOKIE, csrfToken))
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void firstLoginIsRestrictedUntilPasswordChangeAndOldAccessTokenIsRevoked() throws Exception {
        MvcResult login = login("student01", "ChangeMe123!").andExpect(status().isOk()).andReturn();
        String accessToken = json(login).path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/nonexistent").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(put("/api/v1/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"ChangeMe123!","newPassword":"NewPassword123!"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        login("student01", "NewPassword123!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.mustChangePassword").value(false));
    }

    @Test
    void disablingUserImmediatelyInvalidatesExistingAccessToken() throws Exception {
        MvcResult login = login("teacher01", "ChangeMe123!").andExpect(status().isOk()).andReturn();
        String accessToken = json(login).path("data").path("accessToken").asText();
        jdbcTemplate.update("update sys_user set status = 'DISABLED' where username = 'teacher01'");

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void dataScopeAllowsOnlyAssignedLabOrSystemAdministrator() {
        CurrentUser student = new CurrentUser(1001, "student01", Set.of("STUDENT"), Set.of(), false);
        CurrentUser labAdmin = new CurrentUser(1003, "labadmin01", Set.of("LAB_ADMIN"), Set.of(), false);
        CurrentUser systemAdmin = new CurrentUser(1004, "sysadmin01", Set.of("SYSTEM_ADMIN"), Set.of(), false);

        assertThat(dataScopeService.canManageLab(student, 101)).isFalse();
        assertThat(dataScopeService.canManageLab(labAdmin, 101)).isTrue();
        assertThat(dataScopeService.canManageLab(labAdmin, 999)).isFalse();
        assertThat(dataScopeService.canManageLab(systemAdmin, 999)).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", password))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String cookieValue(MvcResult result, String cookieName) {
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return cookies.stream()
                .filter(value -> value.startsWith(cookieName + "="))
                .map(value -> value.substring((cookieName + "=").length(), value.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }
}
