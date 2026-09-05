package com.college.labbooking.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresTestDatabase {
    private static PostgreSQLContainer<?> postgres;
    private static String baseUrl;
    private static String username;
    private static String password;

    private PostgresTestDatabase() {}

    public static synchronized Database schema(String schema) {
        initialize();
        if (!schema.matches("[a-z][a-z0-9_]{0,30}")) {
            throw new IllegalArgumentException("Invalid test schema name");
        }
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
            statement.execute("create schema " + schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create isolated PostgreSQL test schema", exception);
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return new Database(baseUrl + separator + "currentSchema=" + schema, username, password, schema);
    }

    private static void initialize() {
        if (baseUrl != null) {
            return;
        }
        baseUrl = System.getenv("TEST_DB_URL");
        username = System.getenv().getOrDefault("TEST_DB_USERNAME", "lab_booking");
        password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "test-only-password");
        if (baseUrl == null || baseUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:16.15-alpine3.24")
                    .withDatabaseName("lab_booking_test")
                    .withUsername(username)
                    .withPassword(password);
            postgres.start();
            baseUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
    }

    public record Database(String jdbcUrl, String username, String password, String schema) {}
}
