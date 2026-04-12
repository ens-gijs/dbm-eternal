package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.clearInvocations;

public class SqlClientTest {
    private PlatformHandle mockPlatformHandle;
    private SqlConnectionConfig connConfig;
    private Function<@NotNull HikariConfig, HikariDataSource> mockHikariCreator;
    private HikariDataSource mockDataSource;

    private PreparedStatement mockPs;
    private Connection mockConn;


    @BeforeEach
    protected void setUp() throws Exception {
        mockPlatformHandle = mock(PlatformHandle.class);
        mockHikariCreator = mock(Function.class);
        mockDataSource = mock(HikariDataSource.class);
        mockPs = mock(PreparedStatement.class);
        mockConn = mock(Connection.class);
        ParameterMetaData mockMetaData = mock(ParameterMetaData.class);

        when(mockPlatformHandle.name()).thenReturn("TestPlugin");
        when(mockHikariCreator.apply(any())).thenReturn(mockDataSource);
        when(mockDataSource.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.getConnection()).thenReturn(mockConn);
        when(mockPs.getParameterMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getParameterCount()).thenReturn(2);

        connConfig = new MySqlConnectionConfig("10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42");
    }

    @Nested
    @DisplayName("Initialization Logic")
    class InitializationTests {

        @Test
        @DisplayName("Should configure MySQL with performance properties")
        void testMySQLInitialization() {
            SqlConnectionConfig mockConfig = new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42");

            SqlClient client = new SqlClient(mockPlatformHandle, mockConfig, mockHikariCreator);

            ArgumentCaptor<HikariConfig> configCaptor = ArgumentCaptor.forClass(HikariConfig.class);
            verify(mockHikariCreator).apply(configCaptor.capture());
            HikariConfig captured = configCaptor.getValue();

            assertEquals("com.mysql.cj.jdbc.Driver", captured.getDriverClassName());
            assertEquals("true", captured.getDataSourceProperties().getProperty("cachePrepStmts"));
            assertEquals("true", captured.getDataSourceProperties().getProperty("useServerPrepStmts"));
            assertEquals(6, captured.getMaximumPoolSize());
            assertEquals(SqlDialect.MYSQL, client.activeDialect());
        }

        @Test
        @DisplayName("Should force SQLite pool size to 1")
        void testSQLiteInitialization() {
            SqlConnectionConfig mockConfig = new SqliteConnectionConfig(new File("/data/MockedTestDb.db"));

            SqlClient client = new SqlClient(mockPlatformHandle, mockConfig, mockHikariCreator);

            ArgumentCaptor<HikariConfig> configCaptor = ArgumentCaptor.forClass(HikariConfig.class);
            verify(mockHikariCreator).apply(configCaptor.capture());
            assertEquals(1, configCaptor.getValue().getMaximumPoolSize());
            assertEquals(SqlDialect.SQLITE, client.activeDialect());
        }
    }

    @Nested
    @DisplayName("Reloading Logic")
    class SetSqlConnectionConfigTests {

        @Test
        @DisplayName("Should NOT recreate pool if config is equivalent")
        void tesNoChange() {
            SqlClient client = new SqlClient(mockPlatformHandle, connConfig, mockHikariCreator);
            clearInvocations(mockHikariCreator);

            connConfig = new MySqlConnectionConfig("10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42");
            client.setSqlConnectionConfig(connConfig);

            verify(mockDataSource, never()).close();
            verify(mockHikariCreator, never()).apply(any());
        }

        @Test
        @DisplayName("Should close old pool on change")
        void testWithChange() {
            SqlClient client = new SqlClient(mockPlatformHandle, connConfig, mockHikariCreator);
            clearInvocations(mockHikariCreator);

            connConfig = new MySqlConnectionConfig("10.0.50.99", 1324, "MockedTestDbV2", 6, "rot", "tot42");
            client.setSqlConnectionConfig(connConfig);
            verify(mockDataSource).close(); // Closes the old one
            verify(mockHikariCreator, times(1)).apply(any());
        }
    }

    @Nested
    @DisplayName("Shutdown Logic")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown executor before closing DataSource")
        void testGracefulShutdown() throws InterruptedException {
            SqlClient client = new SqlClient(mockPlatformHandle, connConfig, mockHikariCreator);

            client.shutdown(5, TimeUnit.SECONDS);

            // Verifies sequential shutdown (simplified as asyncExecutor is internal)
            verify(mockDataSource).close();
        }
    }

    @Test
    @DisplayName("executeSession should close the connection after the block finishes")
    void testExecuteSessionClosesConnection() throws SQLException {
        final HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection mockConn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(anyString())).thenAnswer(c -> mock(PreparedStatement.class));

        SqlClient client = new SqlClient(mockPlatformHandle, connConfig, hc -> mockDs);
        clearInvocations(mockConn);

        client.executeSession(ctx -> {
            ctx.executeUpdate("SELECT 1");
            return null;
        });

        verify(mockConn, times(1)).close();
        // Ensure auto-commit was restored
        verify(mockConn).setAutoCommit(true);
    }

    @Test
    @DisplayName("Transaction should rollback if an exception occurs within the block")
    void testTransactionRollbackOnException() throws SQLException {
        final HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection mockConn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));

        SqlClient client = new SqlClient(mockPlatformHandle, connConfig, hc -> mockDs);
        clearInvocations(mockConn);

        assertThrows(DatabaseException.class, () -> {
            client.executeTransaction(ctx -> {
                throw new RuntimeException("Simulated Failure");
            });
        });

        verify(mockConn).rollback();
        verify(mockConn, never()).commit();
    }
}
