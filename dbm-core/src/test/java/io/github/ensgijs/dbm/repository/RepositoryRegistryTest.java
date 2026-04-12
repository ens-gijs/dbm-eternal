package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.migration.MigrationParseException;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import org.junit.jupiter.api.*;

import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.ensgijs.dbm.repository.RepositoryRegistry.DB_REGISTRY_RESOURCE_PATH;
import static io.github.ensgijs.dbm.repository.RepositoryRegistry.ResourceWalker;
import static io.github.ensgijs.dbm.util.io.ResourceScanner.ResourceEntry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RepositoryRegistryTest {
    private RepositoryRegistry registry;
    private ResourceWalker mockResourceWalker;

    private PlatformHandle pluginA;
    private PlatformHandle pluginB;
    private SqlDatabaseManager mockManager;

    // --- Helper Classes for Testing ---
    @RepositoryApi("jimmy")
    private interface NonRepoStuff {}
    private static class BadNonRepo extends AbstractRepository implements NonRepoStuff {
        public BadNonRepo(SqlDatabaseManager m) { super(m); }
    }

    @RepositoryApi("fake")
    private interface FakeRepo extends Repository {}

    private interface FakeRepoExtra extends FakeRepo {}

    @RepositoryApi(value = "best", inheritMigrations = false)
    private interface BestRepo extends FakeRepoExtra {}

    private interface NoMigrations extends Repository {}

    @RepositoryApi("side")
    private interface SideRepo extends Repository {}

    private static class NoMigrationsImpl extends AbstractRepository implements NoMigrations {
        public NoMigrationsImpl(SqlDatabaseManager m) {super(m);}
    }

    private static class FakeRepoImpl implements FakeRepoExtra {
        final SqlDatabaseManager manager;
        public FakeRepoImpl(SqlDatabaseManager m) {this.manager = m;}
        @Override public void invalidateCaches() {}
        @Override public @NotNull SqlDatabaseManager getDatabaseManager() { return manager; }

        @Override
        public @NotNull SubscribableEvent<Repository> onCacheInvalidatedEvent() {
            throw new UnsupportedOperationException();
        }
    }

    private static class BetterRepoImpl extends FakeRepoImpl {
        public BetterRepoImpl(SqlDatabaseManager m) { super(m); }
    }

    private static class BestRepoImpl extends BetterRepoImpl implements BestRepo {
        public BestRepoImpl(SqlDatabaseManager m) { super(m); }
    }

    private static class BestRepoWithBadNonRepoStuffImpl extends BestRepoImpl implements NonRepoStuff  {
        public BestRepoWithBadNonRepoStuffImpl(SqlDatabaseManager m) { super(m); }
    }

    private static abstract class AbstractRepoImpl extends FakeRepoImpl {
        public AbstractRepoImpl(SqlDatabaseManager m) { super(m); }
        abstract void barf();
    }

    private static class FakeRepoMissingCtor implements FakeRepo {
        @Override public void invalidateCaches() {}
        @Override public @NotNull SqlDatabaseManager getDatabaseManager() { return null; }
        @Override
        public @NotNull SubscribableEvent<Repository> onCacheInvalidatedEvent() {
            throw new UnsupportedOperationException();
        }
    }

    private static class DiamondInheritanceRepoImpl extends AbstractRepository implements FakeRepo, SideRepo {
        public DiamondInheritanceRepoImpl(SqlDatabaseManager m) { super(m); }
    }

    protected PlatformHandle mockPlugin(@NotNull String name) {
        return mockPlugin(name, Collections.emptyList());
    }

    protected PlatformHandle mockPlugin(
            @NotNull String name,
            @NotNull List<String> dependencies
    ) {
        return new SimplePlatformHandle(name, dependencies);
    }


    @BeforeEach
    protected void setUp() throws Exception {
        mockResourceWalker = mock(ResourceWalker.class);
        registry = spy(new RepositoryRegistry(mockResourceWalker));

        pluginA = mockPlugin("AlphaPlugin");
        // pluginB depends on pluginA
        pluginB = mockPlugin("BetaPlugin", List.of("AlphaPlugin"));
        mockManager = mock(SqlDatabaseManager.class);
        when(mockManager.getPlatformHandle()).thenReturn(pluginA);
    }

    @Test
    @DisplayName("Higher priority nomination should win the election")
    void testHigherPriorityWins() {
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 9);
        registry.nominateRepositoryImplementation(pluginB, BetterRepoImpl.class, 10);
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0);

        registry.closeRegistration();

        var winner = registry.getElectedRepositoryImplementorCandidate(FakeRepo.class);
        assertEquals(BetterRepoImpl.class, winner.implementationType());
        assertEquals(pluginB, winner.platformHandle());
        assertThrows(RepositoryNotRegisteredException.class, () -> registry.getElectedRepositoryImplementorCandidate(FakeRepoExtra.class));
    }

    @Test
    @DisplayName("Dependent plugin should win if priorities are equal")
    void testDependencyPrecedence() {
        // B depends on A
        registry.nominateRepositoryImplementation(pluginB, BetterRepoImpl.class, 5);
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 5);

        registry.closeRegistration();

        var winner = registry.getElectedRepositoryImplementorCandidate(FakeRepo.class);
        assertSame(pluginB, winner.platformHandle(), "Dependent plugin should take precedence");
    }

    @Test
    @DisplayName("Alphabetical name fallback if priority and dependencies are tied")
    void testAlphabeticalFallback() {
        // No dependencies
        PlatformHandle pluginC = mockPlugin("CharliePlugin");
        registry.nominateRepositoryImplementation(pluginC, BetterRepoImpl.class, 0); // Charlie
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0);   // Alpha

        registry.closeRegistration();

        var winner = registry.getElectedRepositoryImplementorCandidate(FakeRepo.class);
        assertEquals(pluginA, winner.platformHandle(), "Alpha should win over Charlie alphabetically");
    }

    @Test
    @DisplayName("The most specific interface wins despite lower priority")
    void testMostSpecificWins() {
        registry.nominateRepositoryImplementation(pluginB, BetterRepoImpl.class, 10);
        registry.nominateRepositoryImplementation(pluginA, BestRepoImpl.class, 0);

        registry.closeRegistration();

        var winner = registry.getElectedRepositoryImplementorCandidate(FakeRepo.class);
        assertSame(BestRepoImpl.class, winner.implementationType());
        assertSame(pluginA, winner.platformHandle());
        assertSame(winner, registry.getElectedRepositoryImplementorCandidate(BestRepo.class));
        assertThrows(RepositoryNotRegisteredException.class, () -> registry.getElectedRepositoryImplementorCandidate(FakeRepoExtra.class));
    }

    @Test
    @DisplayName("Registry should track and return the elected default provider")
    void testDefaultRepositoryProviderElection() {
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0);
        registry.nominateDefaultProvider(FakeRepo.class, mockManager);

        assertTrue(registry.hasDefaultRepository(FakeRepo.class));
        registry.closeRegistration();
        registry.getDefaultRepository(FakeRepo.class);
        verify(mockManager, times(1)).getRepository(FakeRepo.class);
        clearInvocations(mockManager);

        var mockManager2 = mock(SqlDatabaseManager.class);
        registry.getDefaultRepository(FakeRepo.class, mockManager2);
        verify(mockManager, times(1)).getRepository(FakeRepo.class);
        verify(mockManager2, never()).getRepository(any());
    }

    @Test
    @DisplayName("Registry should allow late binding of default repository when none were nominated during voting")
    void testLateBindRepositoryProviderElection() {
        registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0);
        // we do not nominateDefaultProvider before voting closes
        registry.closeRegistration();
        assertNull(registry.getDefaultRepository(FakeRepo.class));
        verify(mockManager, never()).getRepository(any());

        registry.getDefaultRepository(FakeRepo.class, mockManager);
        verify(mockManager, times(1)).getRepository(FakeRepo.class);
        clearInvocations(mockManager);

        assertNull(registry.getDefaultRepository(FakeRepo.class));
        verify(mockManager, times(1)).getRepository(FakeRepo.class);
    }

    @Nested
    class NominateImplementationUsageValidationTests {
        @Test
        @DisplayName("Should throw IllegalStateException when nominating interface lacking @RepositoryApi annotation")
        void testNominatingInterfaceLackingAnnotationThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    registry.nominateRepositoryImplementation(pluginA, NoMigrationsImpl.class, 0)
            );
        }

        @Test
        @DisplayName("Should throw IllegalStateException when nominating interface that extends another with @RepositoryApi but not extending Repository")
        void testNominatingBestRepoWithBadNonRepoStuffThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    registry.nominateRepositoryImplementation(pluginA, BestRepoWithBadNonRepoStuffImpl.class, 0)
            );
        }

        @Test
        @DisplayName("Should throw IllegalStateException when nominating after voting closed")
        void testNominatingAfterClose() {
            registry.closeRegistration();
            assertThrows(IllegalStateException.class, () ->
                    registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0)
            );
        }

        @Test
        @DisplayName("Should throw IllegalStateException when accessing winners before voting closed")
        void testAccessingBeforeClose() {
            registry.nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 0);
            assertThrows(IllegalStateException.class, () -> registry.getElectedRepositoryImplementorCandidate(FakeRepo.class));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when an interface is passed as implementationClass")
        void testNominatingInterfaceThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, FakeRepo.class, 0));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when nominated implementationClass uses a @RepositoryApi interface that doesn't extend Repository")
        void testNominatingInvalidHierarchyThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, BadNonRepo.class, 0));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when nominated implementationClass is an abstract class")
        void testNominatingAbstractImplThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, AbstractRepoImpl.class, 0));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when nominated implementationClass is an interface")
        void testNominatingInterfaceAsImplThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, FakeRepoExtra.class, 0));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when nominated implementationClass lacks required constructor")
        void testNominatingImplLackingRequiredCtorThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, FakeRepoMissingCtor.class, 0));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when nominated implementationClass contains branched inheritance")
        void testNominatingDiamondInheritanceRepoThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.nominateRepositoryImplementation(pluginA, DiamondInheritanceRepoImpl.class, 0));
        }
    }

    @Test
    @DisplayName("Closing voting should fire the ReadyEvent")
    void testReadyEventFired() throws MigrationParseException, ExecutionException, InterruptedException, TimeoutException {
        assertTrue(registry.isAcceptingNominations());
        assertFalse(registry.isReady());
        AtomicInteger ord = new AtomicInteger();
        AtomicInteger configure = new AtomicInteger();
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        registry.register(new SimplePlatformHandle("MyPlugin", Collections.emptyList()), this)
                .onReady(c -> ready.set(ord.incrementAndGet()))
                .onConfigure(c -> configure.set(ord.incrementAndGet()))
                .onPrepare(c -> prepare.set(ord.incrementAndGet()));
        var future = registry.closeRegistration();
        assertFalse(registry.isAcceptingNominations());
        assertTrue(registry.isReady());

        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertEquals(1, configure.get());
        assertEquals(2, prepare.get());
        assertEquals(3, ready.get());
    }

    @Nested
    class RepositoryRegistryScanTest {

        private ResourceEntry mockResourceEntry(Class<?> implType) throws FileNotFoundException {
            return mockResourceEntry(implType.getName());
        }

        private ResourceEntry mockResourceEntry(String implFQCN) throws FileNotFoundException {
            ResourceEntry re = mock(ResourceEntry.class);
            when(re.path()).thenReturn(DB_REGISTRY_RESOURCE_PATH + implFQCN);

            return re;
        }

        @Test
        @DisplayName("Successfully scan and nominate a valid repository resource")
        void testValidScan() throws Exception {
            // 1. Setup mock resource
            ResourceEntry re = mockResourceEntry(FakeRepoImpl.class);

            // 2. Mock the walker to "find" one resource
            doAnswer(invocation -> {
                var consumer = invocation.getArgument(2, ThrowingConsumer.class);
                consumer.accept(re);
                return null;
            }).when(mockResourceWalker).visit(any(), eq(DB_REGISTRY_RESOURCE_PATH), any());

            // 3. Execute scan
            registry.scanPlugin(pluginA, this.getClass().getClassLoader());
            registry.closeRegistration();

            // 4. Verify nomination
            var winner = registry.getElectedRepositoryImplementorCandidate(FakeRepo.class);
            assertEquals(FakeRepoImpl.class, winner.implementationType());
            assertEquals(1, winner.priority(), "Scanned nominations should have priority 1");
            verify(registry, times(1)).nominateRepositoryImplementation(pluginA, FakeRepoImpl.class, 1);
        }

        @Test
        @DisplayName("Should throw exception if implementation class does not exist")
        void testMissingImplementationClass() throws Exception {
            ResourceEntry re = mockResourceEntry("com.example.NonExistentClass");

            doAnswer(invocation -> {
                var consumer = invocation.getArgument(2, ThrowingConsumer.class);
                consumer.accept(re);
                return null;
            }).when(mockResourceWalker).visit(any(), eq(DB_REGISTRY_RESOURCE_PATH), any());

            assertThrows(RepositoryInitializationException.class, () -> registry.scanPlugin(pluginA, this.getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Should throw exception if interface FQCN is used")
        void testUsedInterfaceName() throws Exception {
            ResourceEntry re = mockResourceEntry(FakeRepo.class.getName());

            doAnswer(invocation -> {
                var consumer = invocation.getArgument(2, ThrowingConsumer.class);
                consumer.accept(re);
                return null;
            }).when(mockResourceWalker).visit(any(), eq(DB_REGISTRY_RESOURCE_PATH), any());

            assertThrows(RepositoryInitializationException.class, () -> registry.scanPlugin(pluginA, this.getClass().getClassLoader()));
        }

        @Test
        @DisplayName("Should throw exception implementation hierarchy lacks an @RepositoryApi annotation.")
        void testLacksRepositoryApiAnnotation() throws Exception {
            ResourceEntry re = mockResourceEntry(NoMigrationsImpl.class.getName());

            doAnswer(invocation -> {
                var consumer = invocation.getArgument(2, ThrowingConsumer.class);
                consumer.accept(re);
                return null;
            }).when(mockResourceWalker).visit(any(), eq(DB_REGISTRY_RESOURCE_PATH), any());

            assertThrows(RepositoryInitializationException.class, () -> registry.scanPlugin(pluginA, this.getClass().getClassLoader()));
        }
    }

    @Nested
    @DisplayName("Repository Composition Lifecycle & Flyweights")
    class RepositoryCompositionTest {

        @Nested
        @DisplayName("Feature: Composite Flyweight & Inheritance")
        class FlyweightTests {

            @Test
            @DisplayName("Scenario: Flyweight instance is shared across the inheritance hierarchy")
            void testFlyweightIdentityAcrossHierarchy() {
                // Given: ExtendedLogic extends BaseLogic
                registry.nominateRepositoryComposition(pluginA, EnhancedLogic.class);
                registry.closeRegistration();

                // When: Accessing via base and enhanced class
                EnhancedLogic enhanced = registry.getCompositeRepository(EnhancedLogic.class);
                BaseLogic base = registry.getCompositeRepository(BaseLogic.class);

                // Then: Both must return the exact same instance
                assertNotNull(enhanced, "Enhanced instance should not be null");
                assertSame(enhanced, base, "Both calls must return the exact same object instance");
                assertInstanceOf(EnhancedLogic.class, base, "The instance must be an item of EnhancedLogic");
            }
        }

        @Nested
        @DisplayName("Feature: Lazy Lifecycle & Initialization")
        class LazyInitializationTests {

            @Test
            @DisplayName("Scenario: Composites are only instantiated and initialized on demand")
            void testLazyInstantiation() {
                // Given: A nominated composite
                registry.nominateRepositoryComposition(pluginA,HeavyLogic.class);
                HeavyLogic.instantiationCount.set(0);
                HeavyLogic.initCount.set(0);

                // When: The voting window is closed
                registry.closeRegistration();

                // Then: The constructor should NOT have been called yet
                assertEquals(0, HeavyLogic.instantiationCount.get(), "Constructor should not be called until requested");

                // When: First access
                HeavyLogic instance = registry.getCompositeRepository(HeavyLogic.class);

                // Then: Instantiation and onInitialize should trigger in order
                assertEquals(1, HeavyLogic.instantiationCount.get());
                assertEquals(1, HeavyLogic.initCount.get());
                assertTrue(instance.initialized, "onInitialize must be called before returning");
            }

            @Test
            @DisplayName("Scenario: Custom creator function is respected")
            void testCustomCreator() {
                registry.nominateRepositoryComposition(pluginA, BaseLogic.class, (reg) -> new BaseLogic() {
                    @Override
                    public String toString() { return "CustomInstance"; }
                });
                registry.closeRegistration();

                BaseLogic logic = registry.getCompositeRepository(BaseLogic.class);
                assertEquals("CustomInstance", logic.toString());
            }
        }

        @Nested
        @DisplayName("Feature: Circular Dependency Support")
        class CircularDependencyTests {

            @Test
            @DisplayName("Scenario: Bidirectional dependencies are resolved safely")
            void testSafeCircularReference() {
                // Given: ServiceA depends on ServiceB and vice-versa
                registry.closeRegistration();

                // When: Requesting A (which triggers B, which triggers A)
                ServiceA a = registry.getCompositeRepository(ServiceA.class);
                ServiceB b = registry.getCompositeRepository(ServiceB.class);

                // Then: Circularity is handled via pre-mapping the instance before onInitialize
                assertNotNull(a.other, "ServiceA should have a reference to ServiceB");
                assertNotNull(b.other, "ServiceB should have a reference to ServiceA");
                assertSame(a, b.other, "ServiceB's reference to A should be the same singleton");
                assertSame(b, a.other, "ServiceA's reference to B should be the same singleton");
            }
        }

        @Nested
        @DisplayName("Feature: Cache Invalidation (Logic Layer)")
        class InvalidationTests {

            @Test
            @DisplayName("Scenario: Composite reacts to Repository cache invalidation")
            void testCacheInvalidationPropagation() {
                // Given: A repository and a composite that observes it
                when(mockManager.getPlatformHandle()).thenReturn(pluginA);
                when(mockManager.getRepository(FakeRepoApi.class)).thenAnswer(call -> new FakeRepo(mockManager));

                registry.nominateRepositoryImplementation(pluginA, FakeRepo.class, 0);
                registry.nominateDefaultProvider(FakeRepoApi.class, mockManager);
                registry.closeRegistration();

                assertNotNull(registry.getDefaultRepository(FakeRepoApi.class));

                StatsComposite stats = registry.getCompositeRepository(StatsComposite.class);
                stats.cache.put("key", "value");

                // When: The repository triggers cache invalidation (e.g. during reload)
                stats.repo.invalidateCaches();

                // Then: The composite should have cleared its internal state
                assertTrue(stats.cache.isEmpty(), "Composite should have cleared its internal map");
            }
        }

        // --- Test Dummies ---

        public static class BaseLogic implements RepositoryComposition {}
        public static class EnhancedLogic extends BaseLogic {}

        public static class HeavyLogic implements RepositoryComposition {
            static final AtomicInteger instantiationCount = new AtomicInteger();
            static final AtomicInteger initCount = new AtomicInteger();
            boolean initialized = false;

            public HeavyLogic() { instantiationCount.incrementAndGet(); }

            @Override
            public void onInitialize(RepositoryRegistry registry) {
                initCount.incrementAndGet();
                initialized = true;
            }
        }

        public static class ServiceA implements RepositoryComposition {
            ServiceB other;
            @Override
            public void onInitialize(RepositoryRegistry registry) {
                this.other = registry.getCompositeRepository(ServiceB.class);
            }
        }

        public static class ServiceB implements RepositoryComposition {
            ServiceA other;
            @Override
            public void onInitialize(RepositoryRegistry registry) {
                this.other = registry.getCompositeRepository(ServiceA.class);
            }
        }

        public static class StatsComposite implements RepositoryComposition {
            final java.util.Map<String, String> cache = new java.util.HashMap<>();
            FakeRepoApi repo;

            @Override
            public void onInitialize(RepositoryRegistry registry) {
                repo = registry.getDefaultRepository(FakeRepoApi.class);
                repo.onCacheInvalidatedEvent().subscribe(r -> cache.clear());
            }
        }

        @RepositoryApi("fake2")
        public interface FakeRepoApi extends Repository {}
        public static class FakeRepo extends AbstractRepository implements FakeRepoApi {
            public FakeRepo(SqlDatabaseManager dbm) { super(dbm); }
        }
    }

    @Nested
    @DisplayName("Repository BallotBox & Election Tests")
    class RepositoryBallotBoxTest {

        private RepositoryRegistry.BallotBox<RepositoryRegistry.RepositoryImplementorCandidate, Repository> ballotBox;

        @BeforeEach
        void setUp() {
            ballotBox = new RepositoryRegistry.BallotBox<>();
        }

        @Nested
        @DisplayName("Feature: Specificity and Inheritance")
        class SpecificityTests {

            @Test
            @DisplayName("Scenario: More specific API wins regardless of registration order")
            void testSpecificityWins() {
                // Given: B extends A
                var candidateA = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 0, List.of(RepoA.class), RepoAImpl.class);
                var candidateB = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 0, List.of(RepoB.class, RepoA.class), RepoBImpl.class);

                // When: Nominating base then specific
                ballotBox.nominate(candidateA);
                ballotBox.nominate(candidateB);

                // Then: RepoB wins for both because it is a descendant
                assertEquals(candidateB, ballotBox.getWinner(RepoA.class), "B should trump A for the A-api because it's more specific");
                assertEquals(candidateB, ballotBox.getWinner(RepoB.class));
            }
        }

        @Nested
        @DisplayName("Feature: Unresolvable Ambiguity Detection")
        class AmbiguityTests {

            @Test
            @DisplayName("Scenario: Forked inheritance (B->A and C->A) throws exception (B before C)")
            void testForkedAmbiguity_BC() {
                // Given: B extends A, C extends A (Divergent branches)
                var candidateB = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 0, List.of(RepoB.class, RepoA.class), RepoBImpl.class);
                var candidateC = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 0, List.of(RepoC.class, RepoA.class), RepoCImpl.class);

                ballotBox.nominate(candidateB);

                // When/Then: Nominating C should fail because they both try to satisfy RepoA from unrelated branches
                RepositoryInitializationException ex = assertThrows(RepositoryInitializationException.class,
                        () -> ballotBox.nominate(candidateC));

                System.out.println(ex.getMessage());
                assertTrue(ex.getMessage().contains("Unresolvable ambiguity for " + RepoA.class.getName()));
                assertTrue(ex.getMessage().contains(RepoB.class.getName()));
                assertTrue(ex.getMessage().contains(RepoC.class.getName()));
            }
            @Test
            @DisplayName("Scenario: Forked inheritance (B->A and C->A) throws exception (C before B)")
            void testForkedAmbiguity_CB() {
                // Given: B extends A, C extends A (Divergent branches)
                var candidateB = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 0, List.of(RepoB.class, RepoA.class), RepoBImpl.class);
                var candidateC = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 0, List.of(RepoC.class, RepoA.class), RepoCImpl.class);

                ballotBox.nominate(candidateC);

                // When/Then: Nominating C should fail because they both try to satisfy RepoA from unrelated branches
                RepositoryInitializationException ex = assertThrows(RepositoryInitializationException.class,
                        () -> ballotBox.nominate(candidateB));

                System.out.println(ex.getMessage());
                assertTrue(ex.getMessage().contains("Unresolvable ambiguity for " + RepoA.class.getName()));
                assertTrue(ex.getMessage().contains(RepoB.class.getName()));
                assertTrue(ex.getMessage().contains(RepoC.class.getName()));
            }
        }

        @Nested
        @DisplayName("Feature: Tie Breaking (Priority and Dependencies)")
        class TieBreakingTests {

            @Test
            @DisplayName("Scenario: Higher priority wins for identical API types (low then high)")
            void testPriorityWins_LowHigh() {
                var lowPriority = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 10, List.of(RepoA.class), RepoAImpl.class);
                var highPriority = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 20, List.of(RepoA.class), RepoAImpl.class);

                ballotBox.nominate(lowPriority);
                ballotBox.nominate(highPriority);

                assertEquals(highPriority, ballotBox.getWinner(RepoA.class));
            }
            @Test
            @DisplayName("Scenario: Higher priority wins for identical API types (high then low")
            void testPriorityWins_HighLow() {
                var lowPriority = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 10, List.of(RepoA.class), RepoAImpl.class);
                var highPriority = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 20, List.of(RepoA.class), RepoAImpl.class);

                ballotBox.nominate(highPriority);
                ballotBox.nominate(lowPriority);

                assertEquals(highPriority, ballotBox.getWinner(RepoA.class));
            }

            @Test
            @DisplayName("Scenario: Plugin dependency wins when priorities are equal (A then B)")
            void testDependencyWins_AB() {
                // Given: B depends on A
                var candidateA = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 0, List.of(RepoA.class), RepoAImpl.class);
                var candidateB = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 0, List.of(RepoA.class), RepoAImpl.class);

                ballotBox.nominate(candidateA);
                ballotBox.nominate(candidateB);

                assertEquals(candidateB, ballotBox.getWinner(RepoA.class), "The depending plugin (B) should win over the dependee (A)");
            }

            @Test
            @DisplayName("Scenario: Plugin dependency wins when priorities are equal (B then A)")
            void testDependencyWins_BA() {
                // Given: B depends on A
                var candidateA = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginA, 0, List.of(RepoA.class), RepoAImpl.class);
                var candidateB = new RepositoryRegistry.RepositoryImplementorCandidate(
                        pluginB, 0, List.of(RepoA.class), RepoAImpl.class);

                ballotBox.nominate(candidateB);
                ballotBox.nominate(candidateA);

                assertEquals(candidateB, ballotBox.getWinner(RepoA.class), "The depending plugin (B) should win over the dependee (A)");
            }
        }

        // --- Dummy Hierarchy for Testing ---

        @RepositoryApi
        interface RepoA extends Repository {}

        @RepositoryApi
        interface RepoB extends RepoA {}

        @RepositoryApi
        interface RepoC extends RepoA {} // Divergent branch

        static class RepoAImpl extends AbstractRepository implements RepoA {
            public RepoAImpl(SqlDatabaseManager dbm) { super(dbm); }
        }

        static class RepoBImpl extends AbstractRepository implements RepoB {
            public RepoBImpl(SqlDatabaseManager dbm) { super(dbm); }
        }

        static class RepoCImpl extends AbstractRepository implements RepoC {
            public RepoCImpl(SqlDatabaseManager dbm) { super(dbm); }
        }
    }
}
