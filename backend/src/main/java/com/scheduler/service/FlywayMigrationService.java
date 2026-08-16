package com.scheduler.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlywayMigrationService {

    private final DataSource dataSource;
    // This could be redis or a database table in a real-world scenario to persist across application restarts
    private final Set<String> migratedSchemas = new HashSet<>();

    public synchronized void ensureTenantSchema(String tenantId) {
        String schemaName = "tenant_" + tenantId;

        if (migratedSchemas.contains(schemaName)) {
            log.debug("Schema {} already migrated", schemaName);
            return;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // Check if schema exists
            ResultSet rs = statement.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata WHERE schema_name = '" + schemaName + "'");

            if (!rs.next()) {
                log.info("Creating schema: {}", schemaName);
                statement.execute("CREATE SCHEMA " + schemaName);
            }

            // Run Flyway migration for this schema
            log.info("Running Flyway migration for schema: {}", schemaName);
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schemaName)
                    .locations("classpath:db/migration")
                    // baselineOnMigrate is set to true to allow Flyway to manage existing schemas that may not have been created by Flyway
                    .baselineOnMigrate(true)
                    .load();

            flyway.migrate();
            migratedSchemas.add(schemaName);

            log.info("Schema {} successfully created and migrated", schemaName);

        } catch (SQLException e) {
            log.error("Error ensuring tenant schema: {}", schemaName, e);
            throw new RuntimeException("Failed to create/migrate tenant schema: " + schemaName, e);
        }
    }

    //This is to perform tenant discovery
    public Set<String> getMigratedSchemas() {
        Set<String> tenantSchemas = new HashSet<>();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata " +
                             "WHERE schema_name LIKE 'tenant_%'")) {
            while (resultSet.next()) {
                String schemaName = resultSet.getString("schema_name");
                tenantSchemas.add(schemaName);
                migratedSchemas.add(schemaName);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to discover tenant schemas", e);
        }

        return tenantSchemas;
    }
}
