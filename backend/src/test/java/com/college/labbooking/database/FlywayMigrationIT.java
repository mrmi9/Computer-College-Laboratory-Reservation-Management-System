package com.college.labbooking.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.college.labbooking.support.PostgresTestDatabase;
import com.college.labbooking.support.PostgresTestDatabase.Database;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

class FlywayMigrationIT {
    private static Database database;

    @BeforeAll
    static void migrateEmptyDatabase() {
        database = PostgresTestDatabase.schema("flyway_it");
        Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .defaultSchema(database.schema())
                .locations("classpath:db/migration", "classpath:db/devdata")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        database = null;
    }

    @Test
    void createsCompleteSchemaAndReferenceDataFromEmptyDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, "select count(*) from information_schema.tables where table_schema = 'flyway_it'"))
                    .isGreaterThanOrEqualTo(24);
            assertThat(queryInt(statement, "select count(*) from course_period")).isEqualTo(4);
            assertThat(queryInt(statement, "select count(*) from sys_role")).isEqualTo(4);
            assertThat(queryInt(statement, "select count(*) from sys_permission")).isEqualTo(15);
            assertThat(queryInt(statement, "select count(*) from sys_user")).isEqualTo(4);
            assertThat(queryInt(statement, "select count(*) from lab")).isEqualTo(2);
            assertThat(queryInt(statement, "select count(*) from lab_open_rule")).isEqualTo(40);
        }
    }

    @Test
    void databaseRejectsInvalidPeriodsAndDuplicateEffectiveSlots() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                            "insert into reservation (reservation_no, applicant_id, applicant_type, lab_id, title, purpose, participant_count, booking_date, period_no, status, contact_phone, project_or_course) "
                                    + "values ('INVALID-PERIOD', 1001, 'STUDENT', 101, 't', 'p', 1, current_date + 1, 5, 'PENDING_APPROVAL', '13800000001', 'c')"))
                    .isInstanceOf(PSQLException.class);

            statement.executeUpdate(
                    "insert into reservation (reservation_no, applicant_id, applicant_type, lab_id, title, purpose, participant_count, booking_date, period_no, status, contact_phone, project_or_course) "
                            + "values ('ACTIVE-ONE', 1001, 'STUDENT', 101, 't', 'p', 1, current_date + 2, 1, 'APPROVED', '13800000001', 'c')");
            assertThatThrownBy(() -> statement.executeUpdate(
                            "insert into reservation (reservation_no, applicant_id, applicant_type, lab_id, title, purpose, participant_count, booking_date, period_no, status, contact_phone, project_or_course) "
                                    + "values ('ACTIVE-TWO', 1002, 'TEACHER', 101, 't', 'p', 1, current_date + 2, 1, 'APPROVED', '13800000002', 'c')"))
                    .isInstanceOf(PSQLException.class);
        }
    }

    @Test
    void demoPasswordsAreStrongHashesAndNoRefreshTokenIsSeeded() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("select password_hash from sys_user where id = 1001")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).startsWith("$2a$12$").doesNotContain("ChangeMe123!");
            }
            assertThat(queryInt(statement, "select count(*) from refresh_session")).isZero();
        }
    }

    private static int queryInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
