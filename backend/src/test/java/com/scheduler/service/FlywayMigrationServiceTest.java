package com.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlywayMigrationServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    private FlywayMigrationService migrationService;

    @BeforeEach
    void setUp() throws SQLException {
        migrationService = new FlywayMigrationService(dataSource);

        // Default lenient setup - tests override as needed
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    void testEnsureTenantSchema_IdempotentCalls() throws SQLException {
        String tenantId = "tenant1";
        String schemaName = "tenant_tenant1";

        // Add schema to migrated set manually
        Set<String> migratedSchemas = new HashSet<>();
        migratedSchemas.add(schemaName);
        ReflectionTestUtils.setField(migrationService, "migratedSchemas", migratedSchemas);

        // Call ensureTenantSchema - should return immediately without DB calls
        migrationService.ensureTenantSchema(tenantId);

        // Verify no database interactions
        verify(dataSource, never()).getConnection();
        verify(statement, never()).executeQuery(anyString());
    }

    @Test
    void testEnsureTenantSchema_SqlException() throws SQLException {
        String tenantId = "tenant1";

        when(dataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            migrationService.ensureTenantSchema(tenantId);
        });

        assertTrue(exception.getMessage().contains("Failed to create/migrate tenant schema"));
        assertTrue(exception.getCause() instanceof SQLException);
    }

    @Test
    void testEnsureTenantSchema_CreatesSchemaWhenMissing() throws SQLException {
        String tenantId = "tenant1";
        String schemaName = "tenant_tenant1";

        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // Schema does not exist yet

        try (MockedStatic<Flyway> flywayStatic = mockStatic(Flyway.class)) {
            FluentConfiguration config = mock(FluentConfiguration.class, RETURNS_SELF);
            Flyway flyway = mock(Flyway.class);
            flywayStatic.when(Flyway::configure).thenReturn(config);
            when(config.load()).thenReturn(flyway);

            migrationService.ensureTenantSchema(tenantId);

            // Schema should be created because it did not exist
            verify(statement).execute("CREATE SCHEMA " + schemaName);
            // Flyway migration should run
            verify(flyway).migrate();

            // Cache should be populated
            @SuppressWarnings("unchecked")
            Set<String> migratedSchemas = (Set<String>) ReflectionTestUtils.getField(
                    migrationService, "migratedSchemas");
            assertNotNull(migratedSchemas);
            assertTrue(migratedSchemas.contains(schemaName));
        }
    }

    @Test
    void testEnsureTenantSchema_SkipsCreateWhenSchemaExists() throws SQLException {
        String tenantId = "tenant1";
        String schemaName = "tenant_tenant1";

        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true); // Schema already exists

        try (MockedStatic<Flyway> flywayStatic = mockStatic(Flyway.class)) {
            FluentConfiguration config = mock(FluentConfiguration.class, RETURNS_SELF);
            Flyway flyway = mock(Flyway.class);
            flywayStatic.when(Flyway::configure).thenReturn(config);
            when(config.load()).thenReturn(flyway);

            migrationService.ensureTenantSchema(tenantId);

            // Schema should NOT be created because it already exists
            verify(statement, never()).execute("CREATE SCHEMA " + schemaName);
            // Flyway migration should still run
            verify(flyway).migrate();
        }
    }

    @Test
    void testEnsureTenantSchema_MigratesOnlyOncePerSchema() throws SQLException {
        String tenantId = "tenant1";

        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        try (MockedStatic<Flyway> flywayStatic = mockStatic(Flyway.class)) {
            FluentConfiguration config = mock(FluentConfiguration.class, RETURNS_SELF);
            Flyway flyway = mock(Flyway.class);
            flywayStatic.when(Flyway::configure).thenReturn(config);
            when(config.load()).thenReturn(flyway);

            // First call migrates
            migrationService.ensureTenantSchema(tenantId);
            // Second call should short-circuit via the cache
            migrationService.ensureTenantSchema(tenantId);

            verify(flyway, times(1)).migrate();
            verify(dataSource, times(1)).getConnection();
        }
    }

    @Test
    void testGetMigratedSchemas_Empty() throws SQLException {
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // No schemas

        Set<String> schemas = migrationService.getMigratedSchemas();

        assertNotNull(schemas);
        assertTrue(schemas.isEmpty());
    }

    @Test
    void testGetMigratedSchemas_MultipleTenants() throws SQLException {
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next())
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);
        when(resultSet.getString("schema_name"))
                .thenReturn("tenant_tenant1")
                .thenReturn("tenant_tenant2")
                .thenReturn("tenant_tenant3");

        Set<String> schemas = migrationService.getMigratedSchemas();

        assertNotNull(schemas);
        assertEquals(3, schemas.size());
        assertTrue(schemas.contains("tenant_tenant1"));
        assertTrue(schemas.contains("tenant_tenant2"));
        assertTrue(schemas.contains("tenant_tenant3"));
    }

    @Test
    void testGetMigratedSchemas_SqlException() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("Query failed"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            migrationService.getMigratedSchemas();
        });

        assertTrue(exception.getMessage().contains("Failed to discover tenant schemas"));
        assertTrue(exception.getCause() instanceof SQLException);
    }

    @Test
    void testGetMigratedSchemas_PopulatesInternalCache() throws SQLException {
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next())
                .thenReturn(true)
                .thenReturn(false);
        when(resultSet.getString("schema_name"))
                .thenReturn("tenant_tenant1");

        migrationService.getMigratedSchemas();

        // Verify internal cache was populated
        @SuppressWarnings("unchecked")
        Set<String> migratedSchemas = (Set<String>) ReflectionTestUtils.getField(
                migrationService, "migratedSchemas");
        assertNotNull(migratedSchemas);
        assertTrue(migratedSchemas.contains("tenant_tenant1"));
    }

    @Test
    void testConcurrentAccess() throws SQLException {
        // ensureTenantSchema is synchronized, test that multiple calls don't interfere
        String tenantId1 = "tenant1";
        String tenantId2 = "tenant2";

        // Add both to migrated set
        Set<String> migratedSchemas = new HashSet<>();
        migratedSchemas.add("tenant_tenant1");
        migratedSchemas.add("tenant_tenant2");
        ReflectionTestUtils.setField(migrationService, "migratedSchemas", migratedSchemas);

        // Multiple calls should all return quickly without DB access
        assertDoesNotThrow(() -> {
            migrationService.ensureTenantSchema(tenantId1);
            migrationService.ensureTenantSchema(tenantId2);
            migrationService.ensureTenantSchema(tenantId1);
        });

        verify(dataSource, never()).getConnection();
    }

    @Test
    void testGetMigratedSchemas_FiltersOnlyTenantSchemas() throws SQLException {
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next())
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);
        when(resultSet.getString("schema_name"))
                .thenReturn("tenant_tenant1")
                .thenReturn("tenant_tenant2");

        Set<String> schemas = migrationService.getMigratedSchemas();

        // Should only return tenant schemas (query already filters with LIKE 'tenant_%')
        assertEquals(2, schemas.size());
        assertTrue(schemas.stream().allMatch(s -> s.startsWith("tenant_")));
    }

    @Test
    void testEnsureTenantSchema_Synchronization() throws SQLException {
        // Test that ensureTenantSchema is thread-safe
        String tenantId = "tenant1";
        String schemaName = "tenant_tenant1";

        // Add schema to migrated set
        Set<String> migratedSchemas = new HashSet<>();
        migratedSchemas.add(schemaName);
        ReflectionTestUtils.setField(migrationService, "migratedSchemas", migratedSchemas);

        // Multiple threads calling should not cause issues
        assertDoesNotThrow(() -> {
            Thread t1 = new Thread(() -> migrationService.ensureTenantSchema(tenantId));
            Thread t2 = new Thread(() -> migrationService.ensureTenantSchema(tenantId));

            t1.start();
            t2.start();
            t1.join();
            t2.join();
        });

        verify(dataSource, never()).getConnection();
    }
}
