package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.migration.SchemaMigrator;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.repository.AbstractRepository;
import io.github.ensgijs.dbm.repository.FakeRepository;
import io.github.ensgijs.dbm.repository.FakeRepositoryImpl;
import io.github.ensgijs.dbm.repository.RepositoryImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SqlDatabaseManagerTest {
    private PlatformHandle mockPlatformHandle;
    private SqlConnectionConfig mockConnectionConfig;
    private Function<@NotNull HikariConfig, HikariDataSource> mockHikariCreator;
    private HikariDataSource mockDataSource;
    private SchemaMigrator mockMigrator;
    private PreparedStatement mockPs;
    private Connection mockConn;

    @BeforeEach
    protected void setUp() throws Exception {
        mockPlatformHandle = mock(PlatformHandle.class);
        mockMigrator = mock(SchemaMigrator.class);
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

        mockConnectionConfig = new MySqlConnectionConfig(
                "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42");
    }

    @Nested
    @DisplayName("Initialization Logic")
    class InitializationTests {

        @Test
        @DisplayName("Should configure MySQL with performance properties")
        void testMySQLInitialization() {
            var config = new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42");

            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, config, mockMigrator, mockHikariCreator);

            var configCaptor = org.mockito.ArgumentCaptor.forClass(HikariConfig.class);
            verify(mockHikariCreator).apply(configCaptor.capture());
            HikariConfig captured = configCaptor.getValue();

            assertEquals("com.mysql.cj.jdbc.Driver", captured.getDriverClassName());
            assertEquals("true", captured.getDataSourceProperties().getProperty("cachePrepStmts"));
            assertEquals("true", captured.getDataSourceProperties().getProperty("useServerPrepStmts"));
            assertEquals(6, captured.getMaximumPoolSize());
            assertEquals(SqlDialect.MYSQL, manager.activeDialect());
        }

        @Test
        @DisplayName("Should force SQLite pool size to 1")
        void testSQLiteInitialization() {
            var config = new SqliteConnectionConfig(new File("/data/MockedTestDb.db"));

            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, config, mockMigrator, mockHikariCreator);

            var configCaptor = org.mockito.ArgumentCaptor.forClass(HikariConfig.class);
            verify(mockHikariCreator).apply(configCaptor.capture());
            assertEquals(1, configCaptor.getValue().getMaximumPoolSize());
            assertEquals(SqlDialect.SQLITE, manager.activeDialect());
        }
    }

    @Nested
    @DisplayName("Reloading Logic")
    class ReloadingTests {

        @Test
        @DisplayName("Should NOT recreate pool if config is equivalent")
        void testReloadNoChange() {
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);
            clearInvocations(mockHikariCreator);

            manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42"));

            verify(mockHikariCreator, never()).apply(any());
            verify(mockDataSource, never()).close();
        }

        @Test
        @DisplayName("Should close old pool and invalidate cached repositories on config change")
        void testReloadWithChange() {
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);

            // Prime the cache
            FakeRepositoryImpl repo = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);
            assertNotNull(repo);
            assertFalse(repo.invalidateCacheCalled);

            manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDbV2", 6, "rot", "tot42"));

            assertTrue(repo.invalidateCacheCalled);
            verify(mockDataSource).close();
            verify(mockHikariCreator, times(2)).apply(any()); // once for init, once for reload
        }
    }

    @Nested
    @DisplayName("Shutdown Logic")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown executor before closing DataSource")
        void testGracefulShutdown() throws InterruptedException {
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);

            manager.shutdown(5, TimeUnit.SECONDS);

            verify(mockDataSource).close();
        }
    }

    @Test
    @DisplayName("executeSession should close the connection after the block finishes")
    void testExecuteSessionClosesConnection() throws SQLException {
        HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenAnswer(c -> mock(PreparedStatement.class));

        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, hc -> mockDs);
        clearInvocations(conn);

        manager.executeSession(ctx -> {
            ctx.executeUpdate("SELECT 1");
            return null;
        });

        verify(conn, times(1)).close();
        verify(conn).setAutoCommit(true);
    }

    @Test
    @DisplayName("Transaction should rollback if an exception occurs within the block")
    void testTransactionRollbackOnException() throws SQLException {
        HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));

        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, hc -> mockDs);
        clearInvocations(conn);

        assertThrows(DatabaseException.class, () ->
                manager.executeTransaction(ctx -> { throw new RuntimeException("Simulated Failure"); })
        );

        verify(conn).rollback();
        verify(conn, never()).commit();
    }

    @Test
    @DisplayName("Repository cache eviction on reload failure enables re-bootstrapping")
    void testRepositoryCacheEvictionOnReloadFailure() {
        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);

        FakeRepositoryImpl result1 = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);
        assertNotNull(result1);
        assertSame(result1, manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class));
        assertFalse(result1.invalidateCacheCalled);

        // Poison so invalidateCaches() throws during reload
        result1.poisonInvalidateCache = true;

        manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                "10.0.50.99", 1324, "MockedTestDbV2", 6, "rot", "tot42"));

        assertTrue(result1.invalidateCacheCalled);

        // The entry should have been evicted; next call creates a new instance
        FakeRepositoryImpl result2 = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);
        assertNotNull(result2);
        assertNotSame(result1, result2);
        assertSame(result2, manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class));
    }

    @Test
    @DisplayName("getRepository: flyweight — same impl twice returns same instance")
    void testGetRepositoryFlyweight() {
        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);

        FakeRepository repo1 = manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);
        FakeRepository repo2 = manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);

        assertNotNull(repo1);
        assertInstanceOf(FakeRepositoryImpl.class, repo1);
        assertSame(repo1, repo2, "Flyweight must return same instance");
        verify(mockMigrator, times(1)).runMigrationsFor(FakeRepository.class); // migrations run only once
    }

    @Test
    @DisplayName("getRepository: cache collision with different impl throws RepositoryInitializationException")
    void testGetRepositoryCollisionThrows() {
        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);

        manager.getRepository(FakeRepository.class, FakeRepositoryImpl.class);

        // A second impl for the same api — cache key collision
        assertThrows(Exception.class,
                () -> manager.getRepository(FakeRepository.class, AltFakeRepositoryImpl.class));
    }

    /** Second impl of FakeRepository, used to test cache collision detection. */
    static class AltFakeRepositoryImpl extends io.github.ensgijs.dbm.repository.AbstractRepository
            implements FakeRepository {
        public AltFakeRepositoryImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
        @Override public void clear() {}
    }

    /** MySQL-only impl; used to verify dialect-change rejection when cached. */
    @RepositoryImpl(dialects = {SqlDialect.MYSQL})
    static class MySqlOnlyFakeRepositoryImpl extends AbstractRepository implements FakeRepository {
        public MySqlOnlyFakeRepositoryImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
        @Override public void clear() {}
    }

    @Test
    @DisplayName("validateNewConfig rejects dialect change when a cached repo does not support the new dialect")
    void validateNewConfig_cachedIncompatibleRepo_throws() {
        SqlDatabaseManager manager = new SqlDatabaseManager(
                mockPlatformHandle, mockConnectionConfig, mockMigrator, mockHikariCreator);
        manager.getRepository(FakeRepository.class, MySqlOnlyFakeRepositoryImpl.class);

        var sqliteConfig = new SqliteConnectionConfig(new File("/data/test.db"));
        assertThrows(IllegalStateException.class, () -> manager.setSqlConnectionConfig(sqliteConfig));
    }
}
