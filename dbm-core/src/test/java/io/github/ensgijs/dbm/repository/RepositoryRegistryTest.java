package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import io.github.ensgijs.dbm.sql.SqlClient;
import io.github.ensgijs.dbm.sql.SqlConnectionConfig;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static io.github.ensgijs.dbm.repository.RepositoryRegistry.DB_REGISTRY_RESOURCE_PATH;
import static io.github.ensgijs.dbm.repository.RepositoryRegistry.ResourceWalker;
import static io.github.ensgijs.dbm.util.io.ResourceScanner.ResourceEntry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RepositoryRegistryTest {
    private RepositoryRegistry registry;
    private ResourceWalker mockResourceWalker;
    private PlatformHandle pluginA;
    private PlatformHandle pluginB;
    private SqlDatabaseManager mockManager;

    // -----------------------------------------------------------------------
    // Test api interfaces & impls
    // -----------------------------------------------------------------------

    @RepositoryApi("fake")
    interface FakeRepo extends Repository {
        void put(int key, String value);
        String get(int key);
    }

    @RepositoryApi("side")
    interface SideRepo extends Repository {}

    /** @RepositoryApi on a non-Repository interface — invalid. */
    @RepositoryApi("jimmy")
    interface NonRepoStuff {}

    /** No @RepositoryApi annotation — cannot be published. */
    interface NoMigrations extends Repository {}

    static class FakeRepoImpl extends AbstractRepository implements FakeRepo {
        public FakeRepoImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
    }

    /** Second impl of FakeRepo, used in conflict tests. */
    static class AltFakeRepoImpl extends AbstractRepository implements FakeRepo {
        public AltFakeRepoImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
    }

    static class SideRepoImpl extends AbstractRepository implements SideRepo {
        public SideRepoImpl(SqlClient c) { super(c); }
    }

    static class NoMigrationsImpl extends AbstractRepository implements NoMigrations {
        public NoMigrationsImpl(SqlClient c) { super(c); }
    }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    protected PlatformHandle plugin(String name, String... deps) {
        return new SimplePlatformHandle(name, List.of(deps));
    }

    @BeforeEach
    protected void setUp() {
        mockResourceWalker = mock(ResourceWalker.class);
        registry = spy(new RepositoryRegistry(mockResourceWalker));
        pluginA = plugin("AlphaPlugin");
        pluginB = plugin("BetaPlugin", "AlphaPlugin");
        mockManager = mock(SqlDatabaseManager.class);
        when(mockManager.getPlatformHandle()).thenReturn(pluginA);
        SqlConnectionConfig mockConfig = mock(SqlConnectionConfig.class);
        when(mockConfig.connectionId()).thenReturn("test-db");
        when(mockManager.getSqlConnectionConfig()).thenReturn(mockConfig);
    }

    // -----------------------------------------------------------------------
    // Publish / provider registration
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("EXCLUSIVE publish: second publish for same api throws immediately")
    void testPublishExclusiveConflict() {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);
        SqlConnectionConfig cfg2 = mock(SqlConnectionConfig.class);
        when(cfg2.connectionId()).thenReturn("db2");
        when(manager2.getSqlConnectionConfig()).thenReturn(cfg2);

        registry.publish(FakeRepo.class, mockManager);
        assertThrows(RepositoryInitializationException.class,
                () -> registry.publish(FakeRepo.class, manager2));
    }

    @Test
    @DisplayName("CONTEST publish: conflictResolver picks the winner")
    void testPublishContest() throws Exception {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);

        registry.publish(FakeRepo.class, mockManager, RepositoryRegistry.PublishMode.CONTEST);
        registry.publish(FakeRepo.class, manager2, RepositoryRegistry.PublishMode.CONTEST);

        var future = registry.closeRegistration((api, managers) -> manager2);
        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertTrue(registry.isProvidedBy(FakeRepo.class, manager2));
        assertFalse(registry.isProvidedBy(FakeRepo.class, mockManager));
    }

    @Test
    @DisplayName("CONTEST publish: null conflictResolver causes future to complete exceptionally")
    void testPublishContestNullResolver() throws Exception {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);
        registry.publish(FakeRepo.class, mockManager, RepositoryRegistry.PublishMode.CONTEST);
        registry.publish(FakeRepo.class, manager2, RepositoryRegistry.PublishMode.CONTEST);

        var future = registry.closeRegistration((BiFunction<Class<? extends Repository>, List<SqlDatabaseManager>, SqlDatabaseManager>) null);
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("publish after registration closed throws IllegalStateException")
    void testPublishAfterCloseThrows() {
        registry.closeRegistration();
        assertThrows(IllegalStateException.class,
                () -> registry.publish(FakeRepo.class, mockManager));
    }

    @Test
    @DisplayName("publish with a non-@RepositoryApi interface throws IllegalArgumentException")
    void testPublishNonAnnotatedInterfaceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.publish(NoMigrations.class, mockManager));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("publish with a concrete class (not interface) throws IllegalArgumentException")
    void testPublishNonInterfaceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.publish((Class) FakeRepoImpl.class, mockManager));
    }

    // -----------------------------------------------------------------------
    // get / find / isProvidedBy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("get() returns the repository from the resolved provider")
    void testGetReturnsRepository() throws Exception {
        FakeRepo mockRepo = mock(FakeRepo.class);
        doReturn(mockRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

        registry.bindImpl(FakeRepo.class, FakeRepoImpl.class);
        registry.publish(FakeRepo.class, mockManager);
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertSame(mockRepo, registry.get(FakeRepo.class));
    }

    @Test
    @DisplayName("get() before close throws IllegalStateException")
    void testGetBeforeCloseThrows() {
        assertThrows(IllegalStateException.class, () -> registry.get(FakeRepo.class));
    }

    @Test
    @DisplayName("get() for unpublished api throws RepositoryNotRegisteredException")
    void testGetUnpublishedThrows() throws Exception {
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
        assertThrows(RepositoryNotRegisteredException.class, () -> registry.get(FakeRepo.class));
    }

    @Test
    @DisplayName("find() returns empty Optional for unpublished api")
    void testFindEmptyForUnpublished() throws Exception {
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
        assertTrue(registry.find(FakeRepo.class).isEmpty());
    }

    @Test
    @DisplayName("find() returns the repository when published and bound")
    void testFindReturnsRepository() throws Exception {
        FakeRepo mockRepo = mock(FakeRepo.class);
        doReturn(mockRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

        registry.bindImpl(FakeRepo.class, FakeRepoImpl.class);
        registry.publish(FakeRepo.class, mockManager);
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertEquals(Optional.of(mockRepo), registry.find(FakeRepo.class));
    }

    @Test
    @DisplayName("isProvidedBy() correctly identifies the resolved provider")
    void testIsProvidedBy() throws Exception {
        FakeRepo mockRepo = mock(FakeRepo.class);
        SqlDatabaseManager otherManager = mock(SqlDatabaseManager.class);
        doReturn(mockRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

        registry.bindImpl(FakeRepo.class, FakeRepoImpl.class);
        registry.publish(FakeRepo.class, mockManager);
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertTrue(registry.isProvidedBy(FakeRepo.class, mockManager));
        assertFalse(registry.isProvidedBy(FakeRepo.class, otherManager));
        assertFalse(registry.isProvidedBy(SideRepo.class, mockManager));
    }

    // -----------------------------------------------------------------------
    // bindImpl
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("bindImpl sets the impl binding; findImplementation returns it")
    void testBindImpl() {
        registry.bindImpl(FakeRepo.class, FakeRepoImpl.class);
        assertSame(FakeRepoImpl.class, registry.findImplementation(FakeRepo.class));
    }

    @Test
    @DisplayName("bindImpl after close throws IllegalStateException")
    void testBindImplAfterCloseThrows() {
        registry.closeRegistration();
        assertThrows(IllegalStateException.class,
                () -> registry.bindImpl(FakeRepo.class, FakeRepoImpl.class));
    }

    // -----------------------------------------------------------------------
    // Lifecycle order and idempotency
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onConfigure runs before onReady; second closeRegistration returns false")
    void testRegistrationLifecycleOrder() throws Exception {
        assertTrue(registry.isAcceptingRegistrations());
        assertFalse(registry.isReady());

        AtomicInteger ord = new AtomicInteger();
        AtomicInteger configure = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();

        registry.register(plugin("MyPlugin"), this)
                .onConfigure(c -> configure.set(ord.incrementAndGet()))
                .onReady(r -> ready.set(ord.incrementAndGet()));

        var future = registry.closeRegistration();
        assertFalse(registry.isAcceptingRegistrations());
        assertTrue(registry.isReady());

        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertEquals(1, configure.get());
        assertEquals(2, ready.get());

        assertFalse(registry.closeRegistration().get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("setReadyExecutor dispatches onReady to the given executor")
    void testSetReadyExecutor() throws Exception {
        AtomicReference<Thread> onReadyThread = new AtomicReference<>();
        Thread testThread = Thread.currentThread();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        registry.register(plugin("MyPlugin"), this)
                .onReady(r -> onReadyThread.set(Thread.currentThread()))
                .setReadyExecutor(exec);

        registry.closeRegistration().get(2, TimeUnit.SECONDS);
        exec.shutdown();
        assertTrue(exec.awaitTermination(1, TimeUnit.SECONDS));

        assertNotNull(onReadyThread.get());
        assertNotSame(testThread, onReadyThread.get());
    }

    // -----------------------------------------------------------------------
    // Scan (new resource format: filename=api FQCN, content=impl FQCN)
    // -----------------------------------------------------------------------

    @Nested
    class RepositoryRegistryScanTest {

        private ResourceEntry mockEntry(String apiFQCN, String implFQCN) throws Exception {
            ResourceEntry re = mock(ResourceEntry.class);
            when(re.path()).thenReturn(DB_REGISTRY_RESOURCE_PATH + apiFQCN);
            when(re.asReader()).thenReturn(new BufferedReader(new StringReader(implFQCN)));
            return re;
        }

        private void stubWalker(ResourceEntry... entries) throws Exception {
            doAnswer(inv -> {
                ThrowingConsumer<ResourceEntry> consumer = inv.getArgument(2);
                for (var e : entries) consumer.accept(e);
                return null;
            }).when(mockResourceWalker).visit(any(), eq(DB_REGISTRY_RESOURCE_PATH), any());
        }

        @Test
        @DisplayName("Valid resource registers impl binding")
        void testValidScan() throws Exception {
            stubWalker(mockEntry(FakeRepo.class.getName(), FakeRepoImpl.class.getName()));

            registry.scanPlugin(pluginA, getClass().getClassLoader());

            assertSame(FakeRepoImpl.class, registry.findImplementation(FakeRepo.class));
        }

        @Test
        @DisplayName("Missing api class throws RepositoryInitializationException")
        void testMissingApiClass() throws Exception {
            stubWalker(mockEntry("com.example.NoSuch", FakeRepoImpl.class.getName()));

            assertThrows(RepositoryInitializationException.class,
                    () -> registry.scanPlugin(pluginA, getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Missing impl class throws RepositoryInitializationException")
        void testMissingImplClass() throws Exception {
            stubWalker(mockEntry(FakeRepo.class.getName(), "com.example.NoSuch"));

            assertThrows(RepositoryInitializationException.class,
                    () -> registry.scanPlugin(pluginA, getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Empty resource content throws RepositoryInitializationException")
        void testEmptyContent() throws Exception {
            ResourceEntry re = mock(ResourceEntry.class);
            when(re.path()).thenReturn(DB_REGISTRY_RESOURCE_PATH + FakeRepo.class.getName());
            when(re.asReader()).thenReturn(new BufferedReader(new StringReader("   ")));
            stubWalker(re);

            assertThrows(RepositoryInitializationException.class,
                    () -> registry.scanPlugin(pluginA, getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Conflicting impl bindings for the same api throw RepositoryInitializationException")
        void testImplBindingConflict() throws Exception {
            // First scan: FakeRepoImpl for FakeRepo
            stubWalker(mockEntry(FakeRepo.class.getName(), FakeRepoImpl.class.getName()));
            registry.scanPlugin(pluginA, getClass().getClassLoader());

            // Second scan: AltFakeRepoImpl for FakeRepo (different impl → conflict)
            stubWalker(mockEntry(FakeRepo.class.getName(), AltFakeRepoImpl.class.getName()));
            assertThrows(RepositoryInitializationException.class,
                    () -> registry.scanPlugin(pluginB, getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Scan after close throws IllegalStateException")
        void testScanAfterCloseThrows() {
            registry.closeRegistration();
            assertThrows(IllegalStateException.class,
                    () -> registry.scanPlugin(pluginA, getClass().getClassLoader()));
        }
    }

    // -----------------------------------------------------------------------
    // Repository Composition Lifecycle & Flyweights
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Repository Composition Lifecycle & Flyweights")
    class RepositoryCompositionTest {

        @Nested
        @DisplayName("Flyweight instance is shared across inheritance hierarchy")
        class FlyweightTests {

            @Test
            void testFlyweightIdentityAcrossHierarchy() {
                registry.registerComposition(EnhancedLogic.class);
                registry.closeRegistration();

                EnhancedLogic enhanced = registry.getCompositeRepository(EnhancedLogic.class);
                BaseLogic base = registry.getCompositeRepository(BaseLogic.class);

                assertNotNull(enhanced);
                assertSame(enhanced, base);
                assertInstanceOf(EnhancedLogic.class, base);
            }
        }

        @Nested
        @DisplayName("Lazy instantiation and initialization")
        class LazyInitializationTests {

            @Test
            void testLazyInstantiation() {
                registry.registerComposition(HeavyLogic.class);
                HeavyLogic.instantiationCount.set(0);
                HeavyLogic.initCount.set(0);
                registry.closeRegistration();

                assertEquals(0, HeavyLogic.instantiationCount.get());

                HeavyLogic instance = registry.getCompositeRepository(HeavyLogic.class);

                assertEquals(1, HeavyLogic.instantiationCount.get());
                assertEquals(1, HeavyLogic.initCount.get());
                assertTrue(instance.initialized);
            }

            @Test
            void testCustomCreator() {
                registry.registerComposition(BaseLogic.class, reg -> new BaseLogic() {
                    @Override public String toString() { return "CustomInstance"; }
                });
                registry.closeRegistration();

                assertEquals("CustomInstance", registry.getCompositeRepository(BaseLogic.class).toString());
            }
        }

        @Nested
        @DisplayName("Circular dependency support")
        class CircularDependencyTests {

            @Test
            void testSafeCircularReference() {
                registry.closeRegistration();

                ServiceA a = registry.getCompositeRepository(ServiceA.class);
                ServiceB b = registry.getCompositeRepository(ServiceB.class);

                assertSame(a, b.other);
                assertSame(b, a.other);
            }
        }

        @Nested
        @DisplayName("Cache invalidation propagation from Repository to Composition")
        class InvalidationTests {

            @Test
            void testCacheInvalidationPropagation() throws Exception {
                // A real FakeRepoImpl so that invalidateCaches() actually fires the event
                FakeRepoImpl realRepo = new FakeRepoImpl(mock(SqlClient.class));
                doReturn(realRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

                registry.bindImpl(FakeRepo.class, FakeRepoImpl.class);
                registry.publish(FakeRepo.class, mockManager);
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                StatsComposite stats = registry.getCompositeRepository(StatsComposite.class);
                stats.cache.put("key", "value");
                assertFalse(stats.cache.isEmpty());

                realRepo.invalidateCaches();

                assertTrue(stats.cache.isEmpty(), "Composite should have cleared cache on Repository invalidation");
            }
        }

        // --- Test composition dummies ---

        public static class BaseLogic implements RepositoryComposition {}
        public static class EnhancedLogic extends BaseLogic {}

        public static class HeavyLogic implements RepositoryComposition {
            static final AtomicInteger instantiationCount = new AtomicInteger();
            static final AtomicInteger initCount = new AtomicInteger();
            boolean initialized = false;
            public HeavyLogic() { instantiationCount.incrementAndGet(); }
            @Override
            public void onInitialize(RepositoryRegistry r) {
                initCount.incrementAndGet();
                initialized = true;
            }
        }

        public static class ServiceA implements RepositoryComposition {
            ServiceB other;
            @Override public void onInitialize(RepositoryRegistry r) { other = r.getCompositeRepository(ServiceB.class); }
        }

        public static class ServiceB implements RepositoryComposition {
            ServiceA other;
            @Override public void onInitialize(RepositoryRegistry r) { other = r.getCompositeRepository(ServiceA.class); }
        }

        public static class StatsComposite implements RepositoryComposition {
            final java.util.Map<String, String> cache = new java.util.HashMap<>();
            FakeRepo repo;
            @Override
            public void onInitialize(RepositoryRegistry r) {
                repo = r.get(FakeRepo.class);
                repo.onCacheInvalidatedEvent().subscribe(ignored -> cache.clear());
            }
        }
    }
}
