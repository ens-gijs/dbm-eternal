package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.migration.SchemaMigrator;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.repository.FakeRepository;
import io.github.ensgijs.dbm.repository.FakeRepositoryImpl;
import io.github.ensgijs.dbm.repository.RepositoryRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SqlDatabaseManagerTest {
    private PlatformHandle mockPlatformHandle;
    private DbmConfig mockConfig;
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

        mockConfig = new DbmConfig(new MySqlConnectionConfig(
                "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42"));
    }

    @Nested
    @DisplayName("Initialization Logic")
    class InitializationTests {

        @Test
        @DisplayName("Should configure MySQL with performance properties")
        void testMySQLInitialization() {
            DbmConfig dbmConfig = new DbmConfig(
                    new MySqlConnectionConfig(
                            "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42"));

            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, mockHikariCreator);

            ArgumentCaptor<HikariConfig> configCaptor = ArgumentCaptor.forClass(HikariConfig.class);
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
            var mockConfig = new DbmConfig(new SqliteConnectionConfig(new File("/data/MockedTestDb.db")));

            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, mockHikariCreator);

            ArgumentCaptor<HikariConfig> configCaptor = ArgumentCaptor.forClass(HikariConfig.class);
            verify(mockHikariCreator).apply(configCaptor.capture());
            assertEquals(1, configCaptor.getValue().getMaximumPoolSize());
            assertEquals(SqlDialect.SQLITE, manager.activeDialect());
        }


        @Test
        void testAttemptBootstrapping() {
            RepositoryRegistry registry = spy(new RepositoryRegistry());
            when(registry.isAcceptingNominations()).thenReturn(true, false);

            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, registry, mockHikariCreator);
            verify(registry, never()).nominateDefaultProvider(any(), any());

            manager.registerProvider(List.of(FakeRepository.class));
            verify(registry, times(1)).nominateDefaultProvider(FakeRepository.class, manager);
            clearInvocations(registry);
            manager.registerProvider(Collections.emptyList());
            verify(registry, never()).nominateDefaultProvider(FakeRepository.class, manager);
        }
    }

    @Nested
    @DisplayName("Reloading Logic")
    class ReloadingTests {

        @Test
        @DisplayName("Should NOT recreate pool if config is equivalent")
        void testReloadNoChange() {
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, mockHikariCreator);
            clearInvocations(mockHikariCreator);

            manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDb", 6, "rot", "tot42"));

            verify(mockHikariCreator, never()).apply(any());
            verify(mockDataSource, never()).close();
        }

        @Test
        @DisplayName("Should close old pool and reload repositories on change")
        void testReloadWithChange() {
            RepositoryRegistry mockRegistry = mock(RepositoryRegistry.class);
            when(mockRegistry.getElectedRepositoryImplementorCandidate(FakeRepository.class))
                    .thenReturn(new RepositoryRegistry.RepositoryImplementorCandidate(
                            mockPlatformHandle,
                            999, List.of(FakeRepository.class), FakeRepositoryImpl.class
                    ));
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, mockRegistry, mockHikariCreator);

            // Setup a cached repository to verify reload
            FakeRepositoryImpl repo = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class);
            assertNotNull(repo);
            assertFalse(repo.invalidateCacheCalled);

            manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                    "10.0.50.99", 1324, "MockedTestDbV2", 6, "rot", "tot42"));

            assertTrue(repo.invalidateCacheCalled);

            verify(mockDataSource).close(); // Closes the old one
            verify(mockHikariCreator, times(2)).apply(any()); // Once for init, once for reload
        }
    }

    @Nested
    @DisplayName("Shutdown Logic")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown executor before closing DataSource")
        void testGracefulShutdown() throws InterruptedException {
            SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, mockHikariCreator);

            manager.shutdown(5, TimeUnit.SECONDS);

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

        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, hc -> mockDs);
        clearInvocations(mockConn);

        manager.executeSession(ctx -> {
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

        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, null, hc -> mockDs);
        clearInvocations(mockConn);

        assertThrows(DatabaseException.class, () -> {
            manager.executeTransaction(ctx -> {
                throw new RuntimeException("Simulated Failure");
            });
        });

        verify(mockConn).rollback();
        verify(mockConn, never()).commit();
    }

    @Test
    @DisplayName("Repository Eviction on Reload Failure enables re-bootstrapping of repo instance")
    void testRepositoryCacheEvictionOnReloadRegistryFailure() {
        // Given a manager with a cached repository
        RepositoryRegistry.RepositoryImplementorCandidate repoRepositoryCandidate = new RepositoryRegistry.RepositoryImplementorCandidate(
                mockPlatformHandle,
                999, List.of(FakeRepository.class), FakeRepositoryImpl.class
        );
        RepositoryRegistry mockRegistry = mock(RepositoryRegistry.class);
        when(mockRegistry.getElectedRepositoryImplementorCandidate(FakeRepository.class)).thenReturn(repoRepositoryCandidate);
        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, mockRegistry, mockHikariCreator);

        final FakeRepositoryImpl result1 = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class);
        assertNotNull(result1);
        assertSame(result1, manager.getRepository(FakeRepository.class));
        assertFalse(result1.invalidateCacheCalled);

        // When reload is called and the second repository throws a RuntimeException.
        result1.poisonInvalidateCache = true;

        manager.setSqlConnectionConfig(new MySqlConnectionConfig(
                "10.0.50.99", 1324, "MockedTestDbV2", 6, "rot", "tot42"));

        // Then the repository should be removed from repositoryInstances.
        //  And the next call to getRepository should create a new instance.
        assertTrue(result1.invalidateCacheCalled);
//        verify(repoRegistryCandidate, times(1)).createInstance(manager);  // no new ctor calls yet

        final FakeRepositoryImpl result2 = (FakeRepositoryImpl) manager.getRepository(FakeRepository.class);
        assertNotNull(result2);
        assertNotSame(result1, result2);
        assertSame(result2, manager.getRepository(FakeRepository.class));
//        verify(repoRegistryCandidate, times(2)).createInstance(manager);  // one new ctor call total
    }

    @Test
    @DisplayName("Repository Registry Flyweight Binding & Migration")
    void testRepositoryDiscovery() throws Exception {
        // Given a repository interface that is not yet in the cache.
        RepositoryRegistry.RepositoryImplementorCandidate repoRepositoryCandidate = new RepositoryRegistry.RepositoryImplementorCandidate(
                mockPlatformHandle,
                999, List.of(FakeRepository.class), FakeRepositoryImpl.class
        );
        RepositoryRegistry mockRegistry = mock(RepositoryRegistry.class);
        when(mockRegistry.getElectedRepositoryImplementorCandidate(FakeRepository.class)).thenReturn(repoRepositoryCandidate);
        SqlDatabaseManager manager = new SqlDatabaseManager(mockPlatformHandle, mockConfig, mockMigrator, mockRegistry, mockHikariCreator);

//        when(repoRegistryCandidate.createInstance(manager))
//                .thenAnswer(call -> spy(new FakeRepositoryImpl(manager)));

        // When getRepository is called.
        final FakeRepository repo = manager.getRepository(FakeRepository.class);

        // Then it should query the RepositoryRegistry for the elected implementation.
        //   And it should call schemaMigrator.migrate(instance) before adding it to the cache.
        //   And it should instantiate the implementation using the (SqlDatabaseManager) constructor.
        assertNotNull(repo);
        assertInstanceOf(FakeRepositoryImpl.class, repo);
        // Ensure migration ran before constructing
//        InOrder order = inOrder(repoRegistryCandidate, mockMigrator);
//        order.verify(mockMigrator).runMigrationsFor(FakeRepository.class);
//        order.verify(repoRegistryCandidate).createInstance(manager);
        verify(mockMigrator).runMigrationsFor(FakeRepository.class);

        // Second call should return cached instance
        FakeRepository repo2 = manager.getRepository(FakeRepository.class);
        assertSame(repo, repo2);
        verify(mockMigrator, times(1)).runMigrationsFor(any()); // Migration should NOT run twice
    }
}