package com.scheduler.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantConnectionProviderTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    private TenantConnectionProvider connectionProvider;

    @BeforeEach
    void setUp() {
        connectionProvider = new TenantConnectionProvider(dataSource);
    }

    @Test
    void testGetAnyConnection() throws SQLException {
        // Arrange
        when(dataSource.getConnection()).thenReturn(connection);

        // Act
        Connection result = connectionProvider.getAnyConnection();

        // Assert
        assertNotNull(result);
        assertEquals(connection, result);
        verify(dataSource).getConnection();
    }

    @Test
    void testReleaseAnyConnection() throws SQLException {
        // Act
        connectionProvider.releaseAnyConnection(connection);

        // Assert
        verify(connection).close();
    }

    @Test
    void testGetConnection_Success() throws SQLException {
        // Arrange
        String tenantId = "tenant1";
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);

        // Act
        Connection result = connectionProvider.getConnection(tenantId);

        // Assert
        assertNotNull(result);
        assertEquals(connection, result);
        verify(statement).execute("SET search_path TO \"tenant_tenant1\"");
    }

    @Test
    void testGetConnection_FailureClosesConnection() throws SQLException {
        // Arrange
        String tenantId = "tenant1";
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("Schema error"));

        // Act & Assert
        assertThrows(SQLException.class, () -> {
            connectionProvider.getConnection(tenantId);
        });

        verify(connection).close();
    }

    @Test
    void testReleaseConnection_Success() throws SQLException {
        // Arrange
        String tenantId = "tenant1";
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);

        // Act
        connectionProvider.releaseConnection(tenantId, connection);

        // Assert
        verify(statement).execute("SET search_path TO public");
        verify(connection).close();
    }

    @Test
    void testReleaseConnection_FailureStillClosesConnection() throws SQLException {
        // Arrange
        String tenantId = "tenant1";
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("Reset error"));

        // Act - should not throw, just log warning
        assertDoesNotThrow(() -> {
            connectionProvider.releaseConnection(tenantId, connection);
        });

        // Assert - connection should still be closed
        verify(connection).close();
    }

    @Test
    void testSupportsAggressiveRelease() {
        // Act
        boolean result = connectionProvider.supportsAggressiveRelease();

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsUnwrappableAs() {
        // Act
        boolean result = connectionProvider.isUnwrappableAs(DataSource.class);

        // Assert
        assertFalse(result);
    }

    @Test
    void testUnwrap() {
        // Act
        Object result = connectionProvider.unwrap(DataSource.class);

        // Assert
        assertNull(result);
    }
}
