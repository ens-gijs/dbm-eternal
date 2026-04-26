package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.migration.MigrationParseException;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import io.github.ensgijs.dbm.sql.SqlClient;
import io.github.ensgijs.dbm.sql.SqlConnectionConfig;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import io.github.ensgijs.dbm.util.function.ThrowingBiFunction;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static io.github.ensgijs.dbm.repository.RepositoryRegistry.*;
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

    /**
     * Test helper: routes registration through {@code register(pluginA, this).onConfigure(...)}, the
     * sole supported entry point. Tests still call {@code registry.closeRegistration()} explicitly so
     * they retain control over {@link RegistrationOptions} and timing assertions.
     */
    protected void configure(ThrowingConsumer<RegistrationBootstrappingContext> body) {
        try {
            registry.register(pluginA, this).onConfigure(body);
        } catch (MigrationParseException e) {
            throw new RuntimeException(e);
        }
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
        @SuppressWarnings("unchecked")
        SubscribableEvent<SqlDialect> mockDialectEvent = mock(SubscribableEvent.class);
        when(mockManager.onBeforeDialectChangeEvent()).thenReturn(mockDialectEvent);
    }

    // -----------------------------------------------------------------------
    // Publish / provider registration
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("EXCLUSIVE publish: second publish for same api throws inside onConfigure")
    void testPublishExclusiveConflict() throws Exception {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);
        SqlConnectionConfig cfg2 = mock(SqlConnectionConfig.class);
        when(cfg2.connectionId()).thenReturn("db2");
        when(manager2.getSqlConnectionConfig()).thenReturn(cfg2);

        configure(ctx -> {
            ctx.publish(FakeRepo.class, mockManager);
            assertThrows(RepositoryInitializationException.class,
                    () -> ctx.publish(FakeRepo.class, manager2));
        });
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("CONTEST publish: conflictResolver picks the winner")
    void testPublishContest() throws Exception {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);

        configure(ctx -> {
            ctx.publish(FakeRepo.class, mockManager, ConflictMode.CONTEST);
            ctx.publish(FakeRepo.class, manager2, ConflictMode.CONTEST);
        });

        var future = registry.closeRegistration(RegistrationOptions.empty().providerResolver((api, managers) -> manager2));
        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertTrue(registry.isProvidedBy(FakeRepo.class, manager2));
        assertFalse(registry.isProvidedBy(FakeRepo.class, mockManager));
    }

    @Test
    @DisplayName("CONTEST publish: null conflictResolver causes future to complete exceptionally")
    void testPublishContestNullResolver() {
        SqlDatabaseManager manager2 = mock(SqlDatabaseManager.class);
        configure(ctx -> {
            ctx.publish(FakeRepo.class, mockManager, ConflictMode.CONTEST);
            ctx.publish(FakeRepo.class, manager2, ConflictMode.CONTEST);
        });

        var future = registry.closeRegistration();
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("publish with a non-@RepositoryApi interface throws IllegalArgumentException")
    void testPublishNonAnnotatedInterfaceThrows() throws Exception {
        configure(ctx -> assertThrows(IllegalArgumentException.class,
                () -> ctx.publish(NoMigrations.class, mockManager)));
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("publish with a concrete class (not interface) throws IllegalArgumentException")
    void testPublishNonInterfaceThrows() throws Exception {
        configure(ctx -> assertThrows(IllegalArgumentException.class,
                () -> ctx.publish((Class) FakeRepoImpl.class, mockManager)));
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
    }

    // -----------------------------------------------------------------------
    // get / find / isProvidedBy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("get() returns the repository from the resolved provider")
    void testGetReturnsRepository() throws Exception {
        FakeRepo mockRepo = mock(FakeRepo.class);
        doReturn(mockRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

        configure(ctx -> {
            ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class);
            ctx.publish(FakeRepo.class, mockManager);
        });
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

        configure(ctx -> {
            ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class);
            ctx.publish(FakeRepo.class, mockManager);
        });
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertEquals(Optional.of(mockRepo), registry.find(FakeRepo.class));
    }

    @Test
    @DisplayName("isProvidedBy() correctly identifies the resolved provider")
    void testIsProvidedBy() throws Exception {
        FakeRepo mockRepo = mock(FakeRepo.class);
        SqlDatabaseManager otherManager = mock(SqlDatabaseManager.class);
        doReturn(mockRepo).when(mockManager).getRepository(FakeRepo.class, FakeRepoImpl.class);

        configure(ctx -> {
            ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class);
            ctx.publish(FakeRepo.class, mockManager);
        });
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertTrue(registry.isProvidedBy(FakeRepo.class, mockManager));
        assertFalse(registry.isProvidedBy(FakeRepo.class, otherManager));
        assertFalse(registry.isProvidedBy(SideRepo.class, mockManager));
    }

    // -----------------------------------------------------------------------
    // bindImpl
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("bindImpl sets the impl binding; getImplementationType returns it inside onConfigure")
    void testBindImpl() throws Exception {
        configure(ctx -> {
            ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class);
            assertSame(FakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        });
        registry.closeRegistration().get(1, TimeUnit.SECONDS);
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
                .onReady(r -> {
                    assertFalse(registry.isAcceptingRegistrations());
                    assertTrue(registry.isReady());
                    ready.set(ord.incrementAndGet());
                });

        var future = registry.closeRegistration();

        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertEquals(1, configure.get());
        assertEquals(2, ready.get());

        assertFalse(registry.closeRegistration().get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Lifecycle state transitions: ACCEPTING → CONFIGURING → RESOLVING → READY")
    void testLifecycleStateTransitions() throws Exception {
        assertEquals(Lifecycle.ACCEPTING, registry.lifecycleState());
        assertTrue(registry.isAcceptingRegistrations());
        assertFalse(registry.isReady());

        AtomicReference<Lifecycle> stateInOnConfigure = new AtomicReference<>();
        AtomicReference<Lifecycle> stateInOnReady = new AtomicReference<>();

        registry.register(pluginA, this)
                .onConfigure(ctx -> stateInOnConfigure.set(registry.lifecycleState()))
                .onReady(r -> stateInOnReady.set(registry.lifecycleState()));

        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertEquals(Lifecycle.CONFIGURING, stateInOnConfigure.get(), "onConfigure must run during CONFIGURING");
        assertEquals(Lifecycle.READY, stateInOnReady.get(), "onReady must run during READY (queries permitted)");
        assertEquals(Lifecycle.READY, registry.lifecycleState());
        assertFalse(registry.isAcceptingRegistrations());
        assertTrue(registry.isReady());
    }

    @Test
    @DisplayName("register() during CONFIGURING throws IllegalStateException — no NPE on null pendingRegistrations")
    void testRegisterDuringConfiguringThrows() throws Exception {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        registry.register(pluginA, this).onConfigure(ctx -> {
            try {
                registry.register(plugin("LateComer"), this);
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        assertInstanceOf(IllegalStateException.class, caught.get());
        assertTrue(caught.get().getMessage().contains("CONFIGURING"));
    }

    @Test
    @DisplayName("RegistrationBootstrappingContext is hard-invalidated after its onConfigure callback returns")
    void testCtxInvalidatedAfterOnConfigure() throws Exception {
        AtomicReference<RegistrationBootstrappingContext> escaped = new AtomicReference<>();
        registry.register(pluginA, this).onConfigure(escaped::set);
        registry.closeRegistration().get(1, TimeUnit.SECONDS);

        var ctx = escaped.get();
        assertNotNull(ctx);
        var ex = assertThrows(IllegalStateException.class,
                () -> ctx.publish(FakeRepo.class, mockManager));
        assertTrue(ex.getMessage().contains("outside of its onConfigure callback"));
        assertThrows(IllegalStateException.class,
                () -> ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class));
        assertThrows(IllegalStateException.class,
                () -> ctx.registerComposition(RepositoryCompositionTest.HeavyLogic.class));
    }

    @Test
    @DisplayName("Captured ctx is invalidated even when onConfigure throws")
    void testCtxInvalidatedAfterOnConfigureThrows() throws Exception {
        AtomicReference<RegistrationBootstrappingContext> escaped = new AtomicReference<>();
        registry.register(pluginA, this).onConfigure(ctx -> {
            escaped.set(ctx);
            throw new RuntimeException("boom");
        });
        // Future completes exceptionally because onConfigure threw; we don't care about the cause here.
        var future = registry.closeRegistration();
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));

        var ctx = escaped.get();
        assertNotNull(ctx);
        assertThrows(IllegalStateException.class,
                () -> ctx.publish(FakeRepo.class, mockManager));
    }

    @Test
    @DisplayName("Queries before READY throw with state-aware message")
    void testQueriesRequireReadyState() {
        var ex = assertThrows(IllegalStateException.class, () -> registry.get(FakeRepo.class));
        assertTrue(ex.getMessage().contains("ACCEPTING"));
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
        @DisplayName("Valid resource registers impl binding (resolved after closeRegistration)")
        void testValidScan() throws Exception {
            stubWalker(mockEntry(FakeRepo.class.getName(), FakeRepoImpl.class.getName()));

            registry.scanPlugin(pluginA, getClass().getClassLoader());

            // SUGGEST bindings are deferred to closeRegistration; check after close
            registry.closeRegistration().get(2, TimeUnit.SECONDS);
            assertSame(FakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
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
        @DisplayName("Conflicting SUGGEST impl bindings are deferred to closeRegistration, not scanPlugin")
        void testImplBindingConflict() throws Exception {
            // First scan: FakeRepoImpl for FakeRepo
            stubWalker(mockEntry(FakeRepo.class.getName(), FakeRepoImpl.class.getName()));
            registry.scanPlugin(pluginA, getClass().getClassLoader());

            // Second scan: AltFakeRepoImpl for FakeRepo — conflict is now deferred (SUGGEST semantics)
            stubWalker(mockEntry(FakeRepo.class.getName(), AltFakeRepoImpl.class.getName()));
            // scanPlugin itself no longer throws for SUGGEST conflicts
            assertDoesNotThrow(() -> registry.scanPlugin(pluginB, getClass().getClassLoader()));

            // The conflict surfaces at closeRegistration (no resolver provided)
            var future = registry.closeRegistration();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Scan after close throws IllegalStateException")
        void testScanAfterCloseThrows() throws Exception {
            registry.closeRegistration().get(1, TimeUnit.SECONDS);
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
        @DisplayName("Replaceable composition (abstract intermediary) registration and lookup")
        class ReplaceableCompositionTests {

            @Test
            @DisplayName("Single competitor: abstract key returns instance, concrete key throws")
            void testSingleCompetitorAbstractKeyWins() throws Exception {
                configure(ctx -> ctx.registerComposition(BaseLogicImpl.class));
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                AbstractBaseLogic instance = registry.getCompositeRepository(AbstractBaseLogic.class);
                assertNotNull(instance);
                assertInstanceOf(BaseLogicImpl.class, instance);

                assertThrows(RepositoryInitializationException.class,
                        () -> registry.getCompositeRepository(BaseLogicImpl.class));
            }

            @Test
            @DisplayName("Two competitors + conflict resolver: correct winner returned for abstract key")
            void testTwoCompetitorsWithResolver() throws Exception {
                configure(ctx -> {
                    ctx.registerComposition(BaseLogicImpl.class);
                    ctx.registerComposition(EnhancedLogicImpl.class);
                });
                registry.closeRegistration(RegistrationOptions.empty().compositionResolver((abstractKey, competitors) -> EnhancedLogicImpl.class))
                        .get(1, TimeUnit.SECONDS);

                AbstractBaseLogic instance = registry.getCompositeRepository(AbstractBaseLogic.class);
                assertNotNull(instance);
                assertInstanceOf(EnhancedLogicImpl.class, instance);
            }

            @Test
            @DisplayName("Two competitors + no resolver: closeRegistration future completes exceptionally")
            void testTwoCompetitorsNoResolverThrows() {
                configure(ctx -> {
                    ctx.registerComposition(BaseLogicImpl.class);
                    ctx.registerComposition(EnhancedLogicImpl.class);
                });

                var future = registry.closeRegistration();
                assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            }

            @Test
            @DisplayName("Registering a concrete that extends a concrete composition throws IllegalArgumentException")
            void testExtendingConcreteCompositionThrows() throws Exception {
                configure(ctx -> assertThrows(IllegalArgumentException.class,
                        () -> ctx.registerComposition(DirectLogicSubclass.class)));
                registry.closeRegistration().get(1, TimeUnit.SECONDS);
            }

            @Test
            @DisplayName("Registering a concrete with chain deeper than one abstract throws IllegalArgumentException")
            void testDeepAbstractChainThrows() throws Exception {
                configure(ctx -> assertThrows(IllegalArgumentException.class,
                        () -> ctx.registerComposition(DeepChainImpl.class)));
                registry.closeRegistration().get(1, TimeUnit.SECONDS);
            }
        }

        @Nested
        @DisplayName("getCompositeRepository(Class, fallbackCreator)")
        class FallbackCreatorTests {

            @Test
            @DisplayName("No registration: fallback creator is used")
            void testFallbackCreatorUsedWhenNotRegistered() throws Exception {
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                DirectLogic custom = new DirectLogic() {
                    @Override public String toString() { return "FallbackInstance"; }
                };
                DirectLogic result = registry.getCompositeRepository(DirectLogic.class, reg -> custom);
                assertSame(custom, result);
            }

            @Test
            @DisplayName("With registration: registered creator wins over fallback")
            void testRegisteredCreatorWinsOverFallback() throws Exception {
                DirectLogic registered = new DirectLogic() {
                    @Override public String toString() { return "RegisteredInstance"; }
                };
                configure(ctx -> ctx.registerComposition(DirectLogic.class, reg -> registered));
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                DirectLogic fallback = new DirectLogic() {
                    @Override public String toString() { return "FallbackInstance"; }
                };
                DirectLogic result = registry.getCompositeRepository(DirectLogic.class, reg -> fallback);
                assertSame(registered, result);
                assertEquals("RegisteredInstance", result.toString());
            }
        }

        @Nested
        @DisplayName("Lazy instantiation and initialization")
        class LazyInitializationTests {

            @Test
            void testLazyInstantiation() throws Exception {
                configure(ctx -> ctx.registerComposition(HeavyLogic.class));
                HeavyLogic.instantiationCount.set(0);
                HeavyLogic.initCount.set(0);
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                assertEquals(0, HeavyLogic.instantiationCount.get());

                HeavyLogic instance = registry.getCompositeRepository(HeavyLogic.class);

                assertEquals(1, HeavyLogic.instantiationCount.get());
                assertEquals(1, HeavyLogic.initCount.get());
                assertTrue(instance.initialized);
            }

            @Test
            void testCustomCreator() throws Exception {
                configure(ctx -> ctx.registerComposition(BaseLogic.class, reg -> new BaseLogic() {
                    @Override public String toString() { return "CustomInstance"; }
                }));
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                assertEquals("CustomInstance", registry.getCompositeRepository(BaseLogic.class).toString());
            }
        }

        @Nested
        @DisplayName("Circular dependency support")
        class CircularDependencyTests {

            @Test
            void testSafeCircularReference() throws Exception {
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

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

                configure(ctx -> {
                    ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class);
                    ctx.publish(FakeRepo.class, mockManager);
                });
                registry.closeRegistration().get(1, TimeUnit.SECONDS);

                StatsComposite stats = registry.getCompositeRepository(StatsComposite.class);
                stats.cache.put("key", "value");
                assertFalse(stats.cache.isEmpty());

                realRepo.invalidateCaches();

                assertTrue(stats.cache.isEmpty(), "Composite should have cleared cache on Repository invalidation");
            }
        }

        // --- Test composition dummies ---

        // For direct (non-replaceable) tests — directly implements RC
        public static class DirectLogic implements RepositoryComposition {}

        // Subclass of a concrete composition — illegal (used in rejection tests)
        public static class DirectLogicSubclass extends DirectLogic {}

        // For replaceable tests — abstract intermediary pattern
        public abstract static class AbstractBaseLogic implements RepositoryComposition {}
        public static class BaseLogicImpl extends AbstractBaseLogic {}
        public static class EnhancedLogicImpl extends AbstractBaseLogic {}

        // Deep chain — two abstract levels — illegal (used in rejection tests)
        public abstract static class AbstractMidLevel extends AbstractBaseLogic {}
        public static class DeepChainImpl extends AbstractMidLevel {}

        // For testCustomCreator — directly implements RC (unchanged)
        public static class BaseLogic implements RepositoryComposition {}

        public static class HeavyLogic implements RepositoryComposition {
            static final AtomicInteger instantiationCount = new AtomicInteger();
            static final AtomicInteger initCount = new AtomicInteger();
            boolean initialized = false;
            public HeavyLogic() { instantiationCount.incrementAndGet(); }
            @Override
            public void onInitialize(@NotNull RepositoryRegistry r) {
                initCount.incrementAndGet();
                initialized = true;
            }
        }

        public static class ServiceA implements RepositoryComposition {
            ServiceB other;
            @Override public void onInitialize(@NotNull RepositoryRegistry r) { other = r.getCompositeRepository(ServiceB.class); }
        }

        public static class ServiceB implements RepositoryComposition {
            ServiceA other;
            @Override public void onInitialize(@NotNull RepositoryRegistry r) { other = r.getCompositeRepository(ServiceA.class); }
        }

        public static class StatsComposite implements RepositoryComposition {
            final java.util.Map<String, String> cache = new java.util.HashMap<>();
            FakeRepo repo;
            @Override
            public void onInitialize(@NotNull RepositoryRegistry r) {
                repo = r.get(FakeRepo.class);
                repo.onCacheInvalidatedEvent().subscribe(ignored -> cache.clear());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Binding conflict tests
    // -----------------------------------------------------------------------

    /** Creates a ResourceEntry stub for use in custom ResourceWalker lambdas. */
    private ResourceEntry entry(String apiFQCN, String implFQCN) {
        try {
            ResourceEntry re = mock(ResourceEntry.class);
            when(re.path()).thenReturn(DB_REGISTRY_RESOURCE_PATH + apiFQCN);
            when(re.asReader()).thenReturn(new BufferedReader(new StringReader(implFQCN)));
            return re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class BindingConflictTests {

        @Test
        void suggest_suggest_noResolver_throwsAtClose() throws Exception {
            // Two resource-file SUGGEST entries → no resolver → close fails
            ResourceWalker walker = (cl, path, visitor) -> {
                try {
                    visitor.accept(entry(FakeRepo.class.getName(), FakeRepoImpl.class.getName()));
                    visitor.accept(entry(FakeRepo.class.getName(), AltFakeRepoImpl.class.getName()));
                } catch (Throwable t) { throw new java.io.IOException(t); }
            };
            registry = new RepositoryRegistry(walker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader());
            var future = registry.closeRegistration(); // no resolver
            var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RepositoryInitializationException.class, ex.getCause());
        }

        @Test
        void exclusive_exclusive_throwsInOnConfigure() throws Exception {
            registry = new RepositoryRegistry(mockResourceWalker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class); // first EXCLUSIVE
                        assertThrows(RepositoryInitializationException.class,
                                () -> ctx.bindImpl(FakeRepo.class, AltFakeRepoImpl.class)); // second EXCLUSIVE
                    });
            registry.closeRegistration().get(2, TimeUnit.SECONDS);
        }

        @Test
        void exclusive_displaces_suggest_no_error() throws Exception {
            // Resource file SUGGEST + programmatic EXCLUSIVE → EXCLUSIVE wins silently
            ResourceWalker walker = (cl, path, visitor) -> {
                try { visitor.accept(entry(FakeRepo.class.getName(), AltFakeRepoImpl.class.getName())); }
                catch (Throwable t) { throw new java.io.IOException(t); }
            };
            registry = new RepositoryRegistry(walker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class); // EXCLUSIVE wins
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var future = registry.closeRegistration();
            future.get(2, TimeUnit.SECONDS);
            assertEquals(FakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        }

        @Test
        void exclusive_displaces_contest_with_warning() throws Exception {
            registry = new RepositoryRegistry(mockResourceWalker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, AltFakeRepoImpl.class, ConflictMode.CONTEST);
                        ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class, ConflictMode.EXCLUSIVE); // EXCLUSIVE wins
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var future = registry.closeRegistration();
            future.get(2, TimeUnit.SECONDS);
            assertEquals(FakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        }

        @Test
        void suggest_contest_contestWins_noResolver() throws Exception {
            ResourceWalker walker = (cl, path, visitor) -> {
                try { visitor.accept(entry(FakeRepo.class.getName(), AltFakeRepoImpl.class.getName())); }
                catch (Throwable t) { throw new java.io.IOException(t); }
            };
            registry = new RepositoryRegistry(walker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class, ConflictMode.CONTEST); // CONTEST wins over SUGGEST
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var future = registry.closeRegistration(); // no resolver needed
            future.get(2, TimeUnit.SECONDS);
            assertEquals(FakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        }

        @Test
        void contest_contest_withResolver_resolves() throws Exception {
            registry = new RepositoryRegistry(mockResourceWalker);
            pluginA = plugin("A");
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, FakeRepoImpl.class, ConflictMode.CONTEST);
                        ctx.bindImpl(FakeRepo.class, AltFakeRepoImpl.class, ConflictMode.CONTEST);
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var opts = RegistrationOptions.empty().bindingResolver((api, candidates) -> AltFakeRepoImpl.class);
            var future = registry.closeRegistration(opts);
            future.get(2, TimeUnit.SECONDS);
            assertEquals(AltFakeRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        }
    }

    // -----------------------------------------------------------------------
    // DialectFilterOptions tests
    // -----------------------------------------------------------------------

    /** SQLite-only impl for dialect filter tests. */
    @RepositoryImpl(dialects = {SqlDialect.SQLITE})
    static class SqliteOnlyRepoImpl extends AbstractRepository implements FakeRepo {
        public SqliteOnlyRepoImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
    }

    /** MySQL-only impl for dialect filter tests. */
    @RepositoryImpl(dialects = {SqlDialect.MYSQL})
    static class MySqlOnlyRepoImpl extends AbstractRepository implements FakeRepo {
        public MySqlOnlyRepoImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {}
        @Override public String get(int key) { return null; }
    }

    @Nested
    @DisplayName("RegistrationOptions dialect filter flags")
    class DialectFilterOptionsTests {

        @BeforeEach
        void setDialect() {
            when(mockManager.activeDialect()).thenReturn(SqlDialect.SQLITE);
        }

        @Test
        @DisplayName("logDialectFilterDrops(false) suppresses the drop warning and still resolves the compatible impl")
        void logDialectFilterDrops_false_suppressesWarning() throws Exception {
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, SqliteOnlyRepoImpl.class, ConflictMode.CONTEST);
                        ctx.bindImpl(FakeRepo.class, MySqlOnlyRepoImpl.class, ConflictMode.CONTEST);
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var opts = RegistrationOptions.empty().logDialectFilterDrops(false);
            registry.closeRegistration(opts).get(2, TimeUnit.SECONDS);

            assertEquals(SqliteOnlyRepoImpl.class, registry.getImplementationType(FakeRepo.class));
        }

        @Test
        @DisplayName("throwOnExclusiveDialectFilterDrop(false) demotes the error to a warning and close succeeds")
        void throwOnExclusiveDialectFilterDrop_false_closesNormally() throws Exception {
            registry.register(pluginA, getClass().getClassLoader())
                    .onConfigure(ctx -> {
                        ctx.bindImpl(FakeRepo.class, MySqlOnlyRepoImpl.class); // EXCLUSIVE, but MySQL-only on SQLITE provider
                        ctx.publish(FakeRepo.class, mockManager);
                    });

            var opts = RegistrationOptions.empty().throwOnExclusiveDialectFilterDrop(false);
            registry.closeRegistration(opts).get(2, TimeUnit.SECONDS); // must not throw
        }
    }
}
