package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.util.BubbleUpException;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.migration.MigrationLoader;
import io.github.ensgijs.dbm.migration.MigrationParseException;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import io.github.ensgijs.dbm.util.io.ResourceScanner;
import io.github.ensgijs.dbm.util.objects.OneShotConsumableSubscribableEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A central registry that manages the election of default {@link Repository} API implementation mappings and
 * provides {@link #getDefaultRepository(Class)} to provide centralized access to {@link Repository}'s
 * hosted across plugins and databases.
 * <p>
 * You have the option of using the {@link #globalRegistry()} instance or creating a {@link RepositoryRegistry}
 * instance having its own lifecycle.
 * </p>
 * <p>
 * Additionally, this class facilitates a "voting" system where multiple plugins can provide nominations for competing
 * implementations for the same Repository interface during the bootstrapping phase. This allows one plugin to
 * substitute its own implementation of any {@link Repository} to be used by all plugins.
 * </p>
 */
public final class RepositoryRegistry {
    public static final String DB_REGISTRY_RESOURCE_PATH = "db/registry/";
    // private final static Logger logger = Logger.getLogger("RepositoryRegistry");
    private static RepositoryRegistry globalRegistryInstance;
    private static OneShotConsumableSubscribableEvent<RepositoryRegistry> onGlobalRegistryCreatedEvent;

    private final ResourceWalker resourceWalker;
    private volatile boolean votingClosed = false;
    private Queue<RegistrationHelper> pendingRegistrations = new ConcurrentLinkedQueue<>();

    private final BallotBox<RepositoryImplementorCandidate, Repository> apiImplementorsBallotBox = new BallotBox<>();
    private final BallotBox<RepositoryProviderCandidate, Repository> defaultProvidersBallotBox = new BallotBox<>();
    private final BallotBox<RepositoryCompositionCandidate, RepositoryComposition> compositionsBallotBox = new BallotBox<>();

    /// Flyweight Storage: API/Class -> Singleton Instance
    private final Map<Class<? extends RepositoryComposition>, RepositoryComposition> compositeInstances = new HashMap<>();

    /**
     * @return The singleton global {@link RepositoryRegistry} instance.
     */
    public static RepositoryRegistry globalRegistry() {
        if (globalRegistryInstance == null) {
            boolean created = false;
            synchronized (RepositoryRegistry.class) {
                if (globalRegistryInstance == null) {
                    created = true;
                    globalRegistryInstance = new RepositoryRegistry();
                }
            }
            if (created && onGlobalRegistryCreatedEvent != null)
                onGlobalRegistryCreatedEvent.accept(globalRegistryInstance);
        }
        return globalRegistryInstance;
    }

    public static boolean isGlobalRegistryCreated() {
        return globalRegistryInstance != null;
    }

    @FunctionalInterface
    @VisibleForTesting
    interface ResourceWalker {
        void visit(
                @NotNull final ClassLoader classLoader,
                @NotNull final String rootPath,
                @NotNull final ThrowingConsumer<ResourceScanner.ResourceEntry> visitor
        ) throws IOException;
    }

    public RepositoryRegistry() {
        this.resourceWalker = ResourceScanner::visit;
    }

    @VisibleForTesting
    RepositoryRegistry(ResourceWalker resourceWalker) {
        this.resourceWalker = resourceWalker;
    }

    public boolean isAcceptingNominations() {
        return !votingClosed;
    }

    public boolean isReady() {
        return votingClosed;
    }

    public static final class RegistrationHelper implements Comparable<RegistrationHelper> {
        private static final AtomicInteger ORDINAL_PROVIDER = new AtomicInteger();
        private final int ordinal;
        private final @NotNull PlatformHandle platformHandle;
        private @Nullable ThrowingConsumer<RegistrationBootstrappingContext> onConfigure;
        private @Nullable ThrowingConsumer<RepositoryRegistry> onPrepare;
        private @Nullable ThrowingConsumer<RepositoryRegistry> onReady;
        private boolean mutable = true;

        private RegistrationHelper(@NotNull PlatformHandle platformHandle) {
            this.ordinal = ORDINAL_PROVIDER.incrementAndGet();
            this.platformHandle = platformHandle;
        }

        public RegistrationHelper onConfigure(ThrowingConsumer<RegistrationBootstrappingContext> op) {
            if (!mutable) throw new IllegalStateException();
            this.onConfigure = op;
            return this;
        }

        public RegistrationHelper onPrepare(ThrowingConsumer<RepositoryRegistry> op) {
            if (!mutable) throw new IllegalStateException();
            this.onPrepare = op;
            return this;
        }

        public RegistrationHelper onReady(ThrowingConsumer<RepositoryRegistry> op) {
            if (!mutable) throw new IllegalStateException();
            this.onReady = op;
            return this;
        }

        @Override
        public int compareTo(@NotNull RegistrationHelper that) {
            if (this == that) return 0;
            int depComp = PlatformHandle.dependencyComparator(this.platformHandle, that.platformHandle);
            return depComp != 0 ? depComp : Integer.compare(this.ordinal, that.ordinal);
        }
    }


    public static final class RegistrationBootstrappingContext {
        private final @NotNull RepositoryRegistry registry;
        private final @NotNull PlatformHandle platformHandle;

        private RegistrationBootstrappingContext(@NotNull RepositoryRegistry registry, @NotNull PlatformHandle platformHandle) {
            this.registry = registry;
            this.platformHandle = platformHandle;
        }

        public @NotNull RepositoryRegistry registry() {
            return registry;
        }

        public @NotNull PlatformHandle platformHandle() {
            return platformHandle;
        }

        /**
         * Nominates a preferred {@link SqlDatabaseManager} for hosting the specified repositoryApiType.<br/>
         * Nominations may be overridden by higher priority event handlers.
         * <p>
         * Default repository instances can be retrieved from {@link RepositoryRegistry#getDefaultRepository(Class)}
         * after bootstrapping is complete.
         * </p>
         *
         * @param repositoryApiType Repository API interface type. This must not be a concretion and must have the {@link RepositoryApi}.
         * @param preferredManager  The preferred {@link SqlDatabaseManager} instance to used to access the repositories' data.
         */
        public <T extends Repository> void nominateDefaultProvider(@NotNull Class<T> repositoryApiType, @NotNull SqlDatabaseManager preferredManager) throws RepositoryInitializationException {
            registry.nominateDefaultProvider(repositoryApiType, preferredManager);
        }

        /**
         * Nominates a preferred implementation class for the specified repositoryImplementationType.<br/>
         * Allows for the overriding of {@code resources/db/registry/..} implementation mappings.<br/>
         * You do not need to repeat mappings already declared via {@code resources/db/registry/..}<br/>
         */
        public <I extends Repository> void nominateImplementation(@NotNull Class<? extends I> repositoryImplementationType, int priority) throws RepositoryInitializationException {
            registry.nominateRepositoryImplementation(platformHandle, repositoryImplementationType, priority);
        }

        /**
         * Nominates a composite for the registry. Overwrites any previous nomination for the same implementation tree.
         */
        public <T extends RepositoryComposition> void nominateRepositoryComposition(
                @NotNull Class<T> implementationClass
        ) {
            registry.nominateRepositoryComposition(platformHandle, implementationClass);
        }

        /**
         * Nominates a composite for the registry. Overwrites any previous nomination for the same implementation tree.
         */
        public <T extends RepositoryComposition> void nominateRepositoryComposition(
                @NotNull Class<T> implementationClass,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
        ) {
            registry.nominateRepositoryComposition(platformHandle, implementationClass, creator);
        }
    }

    /**
     * Scans the given plugin object's classpath for migration and registry resources and
     * returns a {@link RegistrationHelper} for configuring lifecycle callbacks.
     * <p>
     * The parameter is typed as {@link Object} so that platform-specific plugin types
     * (e.g., {@code org.bukkit.plugin.Plugin}) do not need to be on the compile-time classpath
     * of this module. The plugin's {@link ClassLoader} is extracted via
     * {@code plugin.getClass().getClassLoader()}.
     * </p>
     *
     * @param platformHandle Identifies the registering plugin.
     * @param plugin         Any object whose {@link ClassLoader} scopes the plugin's resources.
     * @throws RepositoryInitializationException If JAR resources cannot be resolved.
     * @throws MigrationParseException           If any migration file cannot be parsed.
     * @throws IllegalStateException             If registration has already been closed.
     */
    public RegistrationHelper register(@NotNull PlatformHandle platformHandle, @NotNull Object plugin) throws RepositoryInitializationException, MigrationParseException {
        return register(platformHandle, plugin.getClass().getClassLoader());
    }

    /**
     * Scans the given classpath scope for migration and registry resources and returns a
     * {@link RegistrationHelper} for configuring lifecycle callbacks.
     *
     * @param platformHandle Identifies the registering plugin.
     * @param scope          The {@link ClassLoader} used to discover classpath resources.
     * @throws RepositoryInitializationException If JAR resources cannot be resolved.
     * @throws MigrationParseException           If any migration file cannot be parsed.
     * @throws IllegalStateException             If registration has already been closed.
     */
    public RegistrationHelper register(@NotNull PlatformHandle platformHandle, @NotNull ClassLoader scope) throws RepositoryInitializationException, MigrationParseException {
        if (votingClosed) throw new IllegalStateException("Registration is closed!");
        scanPlugin(platformHandle, scope);
        RegistrationHelper helper = new RegistrationHelper(platformHandle);
        pendingRegistrations.add(helper);
        return helper;
    }

    /**
     * Resolves all conflicts and determines the winner for every repository type.
     * <p>Registration onConfigure and onReady callbacks are run in a background thread.</p>
     * @return If the returned future contains TRUE then all requested registrations completed successfully.
     * Only a single call will ever see TRUE. FALSE is returned for all calls which came after the first.
     * <p>Completes exceptionally if there was an error executing any registrations onConfigure or onReady callbacks,
     * all registrations will have had a chance for their onConfigure and onReady events to be called even if
     * an earlier registration threw. However, if a registrations onConfigure throws then its onReady will not be
     * called.</p>
     */
    @VisibleForTesting
    public synchronized CompletableFuture<Boolean> closeRegistration() {
        if (votingClosed) return CompletableFuture.completedFuture(false);
        votingClosed = true;
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final List<RegistrationHelper> registrations = new LinkedList<>(this.pendingRegistrations);
        this.pendingRegistrations = null;
        Thread.ofVirtual().start(() -> {
            RepositoryInitializationException err = null;
            var iter = registrations.listIterator();
            while (iter.hasNext()) {
                var r = iter.next();
                r.mutable = false;
                if (r.onConfigure != null) {
                    try {
                        r.onConfigure.accept(new RegistrationBootstrappingContext(this, r.platformHandle));
                    } catch (Throwable ex) {
                        if (err == null) err = new RepositoryInitializationException("Error while executing RegistrationHelper(s).");
                        err.addSuppressed(ex);
                        iter.remove();
                    }
                }
            }
            iter = registrations.listIterator();
            while (iter.hasNext()) {
                var r = iter.next();
                r.mutable = false;
                if (r.onPrepare != null) {
                    try {
                        r.onPrepare.accept(this);
                    } catch (Throwable ex) {
                        if (err == null) err = new RepositoryInitializationException("Error while executing RegistrationHelper(s).");
                        err.addSuppressed(ex);
                        iter.remove();
                    }
                }
            }
            for (var r : registrations) {
                if (r.onReady != null) {
                    try {
                        r.onReady.accept(this);
                    } catch (Throwable ex) {
                        if (err == null) err = new RepositoryInitializationException("Error while executing RegistrationHelper(s).");
                        err.addSuppressed(ex);
                    }
                }
            }
            if (err != null) future.completeExceptionally(err);
            else future.complete(true);
        });
        return future;
    }

    /**
     * Checks if a default provider has been nominated and elected for the given repository type.
     *
     * @param <I>            The repository interface type.
     * @param repositoryType The class literal of the repository interface to check.
     * @return {@code true} if a {@link SqlDatabaseManager} has been nominated as the
     * default provider for this type; {@code false} otherwise.
     * @apiNote This method may be called both before and after voting has closed. It's the callers responsibility to
     * check either {@link #isReady()} or {@link #isAcceptingNominations()} to act intelligently.
     */
    public <I extends Repository> boolean hasDefaultRepository(@NotNull Class<I> repositoryType) {
        return defaultProvidersBallotBox.containsKey(repositoryType);
    }

    /**
     * Retrieves the repository instance from the default provider assigned to the given type.
     * <p>
     * In a multi-database environment, different {@link SqlDatabaseManager} instances may
     * register themselves as the "provider" for specific repository interfaces. This method
     * bridges the gap between the global registry and the specific manager owning the data.
     * </p>
     * <p>
     * <b>Note:</b> This requires that a default provider was nominated while voting was for this {@code repositoryType}.
     * </p>
     *
     * @param <I>            The specific interface type extending {@link Repository}.
     * @param repositoryType The class literal of the repository interface to retrieve.
     * @return The instantiated and ready to use repository instance from the default manager,
     * or {@code null} if no default provider has been assigned to this type.
     * @throws IllegalStateException If {@link #isReady()} would return false (voting phase not yet complete).
     * @apiNote Check {@link #isReady()} before calling.
     */
    public synchronized @Nullable <I extends Repository> I getDefaultRepository(@NotNull Class<I> repositoryType) {
        if (!votingClosed) throw new IllegalStateException("Voting has not yet been closed!");
        var candidate = defaultProvidersBallotBox.getWinner(repositoryType);
        return candidate != null ? candidate.manager.getRepository(repositoryType) : null;
    }

    /**
     * Retrieves the repository instance from the default provider assigned to the given type if there is one,
     * if one was not nominated while voting was open then the provided volunteeringManager is nominated as the
     * single de-facto candidate and will be used as the providing manager for all future calls.
     * <p>
     * In a multi-database environment, different {@link SqlDatabaseManager} instances may
     * register themselves as the "provider" for specific repository interfaces. This method
     * bridges the gap between the global registry and the specific manager owning the data.
     * </p>
     * <p>
     * <b>Note:</b> Check {@link #isReady()} before calling.
     * </p>
     *
     * @param <I>            The specific interface type extending {@link Repository}.
     * @param repositoryType The class literal of the repository interface to retrieve.
     * @return The instantiated and ready to use repository instance from the default manager,
     * or {@code null} if no default provider has been assigned to this type.
     * @throws IllegalStateException If {@link #isReady()} would return false (voting phase not yet complete).
     * @apiNote Check {@link #isReady()} before calling.
     */
    public synchronized @Nullable <I extends Repository> I getDefaultRepository(@NotNull Class<I> repositoryType, @NotNull SqlDatabaseManager volunteeringManager) {
        if (!votingClosed) throw new IllegalStateException("Voting has not yet been closed!");
        var candidate = defaultProvidersBallotBox.getWinner(repositoryType);
        if (candidate != null) return candidate.manager.getRepository(repositoryType);
        defaultProvidersBallotBox.nominate(new RepositoryProviderCandidate(
                volunteeringManager, Repository.collectAllImplementedRepoApis(repositoryType)
        ));
        return volunteeringManager.getRepository(repositoryType);
    }

    /**
     * Scans a plugin's JAR file for repository registration resources and migrations.
     * <p>
     * This method performs the following actions:
     * <ol>
     * <li>Ensures the registry is still in the "Voting Phase".</li>
     * <li>Triggers {@link MigrationLoader#loadMigrations(PlatformHandle, ClassLoader)} for the provided plugin.</li>
     * <li>Iterates through the plugin's JAR entries searching for files within
     * {@code DB_REGISTRY_RESOURCE_PATH} (e.g., {@code db/registry/}).</li>
     * <li>Treats the filename as the FQCN (Fully Qualified Class Name) of the Repository concrete implementation class.</li>
     * <li>Resolves these classes using the plugin's own ClassLoader.</li>
     * <li>Calls {@link #nominateRepositoryImplementation} to enter the candidate into the ballot box with a default priority of 1.</li>
     * </ol>
     * </p>
     *
     * @param platformHandle The plugin whose JAR file should be scanned.
     * @param scope The class loader used to locate jar resources.
     * @throws MigrationParseException If an error occurred while loading db migration resources.
     * @throws RepositoryInitializationException If an I/O error occurs or classes cannot be resolved.
     * @throws IllegalStateException If called after {@link #closeRegistration()} has been invoked.
     */
    @VisibleForTesting
    void scanPlugin(@NotNull PlatformHandle platformHandle, @NotNull ClassLoader scope) throws RepositoryInitializationException, MigrationParseException {
        if (votingClosed) throw new IllegalStateException("Registry proposals are closed!");

        MigrationLoader.loadMigrations(platformHandle, scope);
        try {
            resourceWalker.visit(scope, DB_REGISTRY_RESOURCE_PATH, entry -> {
                String implName = entry.path().substring(DB_REGISTRY_RESOURCE_PATH.length());
                Class<? extends Repository> implClass = Class.forName(implName, false, scope)
                        .asSubclass(Repository.class);
                nominateRepositoryImplementation(platformHandle, implClass, 1);
            });
        } catch (Exception ex) {
            throw new RepositoryInitializationException(
                    "Error while scanning plugin jar resources: " + platformHandle.name(), BubbleUpException.unwrap(ex));
        }
    }

    /**
     * Nominates a default provider for the specified repository type. The winning nomination is the one that
     * was nominated last before voting was closed.
     *
     * @param repositoryApiType Repository API interface type. This must not be a concretion and must have the {@link RepositoryApi}.
     * @param preferredManager The preferred {@link SqlDatabaseManager} instance to used to access the repositories' data.
     * @apiNote Caller should check {@link #isAcceptingNominations()} before calling this method outside the
     * lifecycle start callback.
     */
    public synchronized void nominateDefaultProvider(@NotNull Class<? extends Repository> repositoryApiType, @NotNull SqlDatabaseManager preferredManager) {
        if (votingClosed) throw new IllegalStateException("Registry proposals are closed!");
        if (!repositoryApiType.isInterface() || !Repository.class.isAssignableFrom(repositoryApiType)) {
            throw new IllegalArgumentException(repositoryApiType.getName() + " either is not an interface or does not implement Repository.");
        }
        if (repositoryApiType.getAnnotation(RepositoryApi.class) == null) {
            throw new IllegalArgumentException(repositoryApiType.getName() + " is missing the required @RepositoryApi annotation.");
        }

        defaultProvidersBallotBox.nominate(new RepositoryProviderCandidate(
                preferredManager, Repository.collectAllImplementedRepoApis(repositoryApiType)
        ));
    }

    /**
     * Nominates a candidate implementation for a specific Repository API and its ancestor API's.
     *
     * @param platformHandle      The plugin nominating this implementation.
     * @param repositoryImplementationType The concrete class implementing a repository API.
     * @param priority            The priority of this candidate (higher values take precedence).
     *                            Registry impl mappings found in plugin {@code resources/db/registry/*} are given priority=1.
     * @throws IllegalStateException If called after {@link #closeRegistration()}.
     * @apiNote Caller should check {@link #isAcceptingNominations()} before calling this method outside the
     * lifecycle start callback.
     */
    public synchronized void nominateRepositoryImplementation(
            @NotNull PlatformHandle platformHandle,
            @NotNull Class<? extends Repository> repositoryImplementationType,
            int priority
    ) throws RepositoryInitializationException {
        if (votingClosed) throw new IllegalStateException("Registry proposals are closed!");

        if (repositoryImplementationType.isInterface() || Modifier.isAbstract(repositoryImplementationType.getModifiers())) {
            throw new IllegalArgumentException("repositoryImplementationType (" + repositoryImplementationType.getName() + ") must be a non-abstract class");
        }

        try {
            repositoryImplementationType.getConstructor(SqlDatabaseManager.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("Class " + repositoryImplementationType.getName()
                    + " is missing the required ctor(SqlDatabaseManager) constructor. Hint: if this is an internal class it must also be static.", ex);
        }

        apiImplementorsBallotBox.nominate(new RepositoryImplementorCandidate(
                platformHandle, priority, Repository.collectAllImplementedRepoApis(repositoryImplementationType), repositoryImplementationType));
    }

    /**
     * Nominates a composite for the registry. Overwrites any previous nomination for the same implementation tree.
     * @apiNote Caller should check {@link #isAcceptingNominations()} before calling this method outside the
     * lifecycle start callback.
     */
    public synchronized <T extends RepositoryComposition> void nominateRepositoryComposition(
            @NotNull PlatformHandle platformHandle,
            @NotNull Class<T> compositionType
    ) {
        if (votingClosed) throw new IllegalStateException("Registry proposals are closed!");
        nominateRepositoryComposition(platformHandle, compositionType, getCompositionCreator(compositionType));
    }

    /**
     * Nominates a composite with a custom creator. Subject to the voting window.
     * @apiNote Caller should check {@link #isAcceptingNominations()} before calling this method outside the
     * lifecycle start callback.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends RepositoryComposition> void nominateRepositoryComposition(
            @NotNull PlatformHandle platformHandle,
            @NotNull Class<T> compositionType,
            @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
    ) {
        if (votingClosed) throw new IllegalStateException("Registry proposals are closed!");
        if (compositionType.isInterface() || Modifier.isAbstract(compositionType.getModifiers())) {
            throw new IllegalArgumentException("implementationClass must be a non-interface non-abstract class.");
        }

        compositionsBallotBox.nominate(new RepositoryCompositionCandidate(
                platformHandle,
                compositionType,
                creator));
    }

    /**
     * Retrieves the flyweight instance of a composite.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends RepositoryComposition> @NotNull T getCompositeRepository(@NotNull Class<T> compositionType) throws RepositoryInitializationException {
        if (!votingClosed) throw new IllegalStateException("Cannot access composite flyweights before voting is closed.");
        RepositoryComposition ret = compositeInstances.get(compositionType);
        if (ret != null) return (T) ret;

        var candidate = compositionsBallotBox.getWinner(compositionType);
        if (candidate == null) {
            candidate = new RepositoryCompositionCandidate(
                    null,
                    compositionType,
                    getCompositionCreator(compositionType));
            compositionsBallotBox.nominate(candidate);
        }

        try {
            ret = Objects.requireNonNull(candidate.creator.apply(this));
            for (var api : candidate.apiTypes) {
                if (compositeInstances.put(api, ret) != null) {
                    throw new IllegalStateException();
                }
            }
            ret.onInitialize(this);
            return (T) ret;
        } catch (Throwable ex) {
            if (ret != null) {
                for (var api : candidate.apiTypes) {
                    compositeInstances.remove(api, ret);
                }
            }
            throw new RepositoryInitializationException("Error while creating instance of " + compositionType.getName(), ex);
        }
    }

    private <T extends RepositoryComposition> @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> getCompositionCreator(@NotNull Class<T> compositionType) {
        Constructor<T> ctor0 = null;
        Constructor<T> ctor1;
        try {
            ctor1 = compositionType.getConstructor(RepositoryRegistry.class);
            ctor1.setAccessible(true);
        } catch (Exception ignored) {
            ctor1 = null;
        }
        if (ctor1 == null) {
            try {
                ctor0 = compositionType.getDeclaredConstructor();
                ctor0.setAccessible(true);
            } catch (Exception ignored) {
                ctor0 = null;
            }
        }

        if (ctor1 == null && ctor0 == null)
            throw new IllegalArgumentException("Composite " + compositionType.getName() + " must have a constructor taking only RepositoryRegistry or a no-args constructor if no creator is provided.");


        final Constructor<T> finalCtor0 = ctor0;
        final Constructor<T> finalCtor1 = ctor1;
        return (reg) -> {
            if (finalCtor1 != null)
                return finalCtor1.newInstance(reg);
            else
                return finalCtor0.newInstance();
        };
    }

    /**
     * Retrieves the winning candidate for a given repository interface.
     * @param repositoryType The interface to lookup.
     * @return The elected {@link RepositoryImplementorCandidate}.
     * @throws IllegalStateException If voting has not been closed yet.
     * @throws RepositoryNotRegisteredException If no implementation was registered for this type.
     * @apiNote Check {@link #isReady()} before calling.
     */
    @NotNull
    public synchronized RepositoryImplementorCandidate getElectedRepositoryImplementorCandidate(
            Class<? extends Repository> repositoryType
    ) throws IllegalStateException, RepositoryInitializationException {
        if (!votingClosed) throw new IllegalStateException("Voting has not yet been closed!");
        var ret = apiImplementorsBallotBox.getWinner(repositoryType);
        if (ret != null) return ret;
        throw new RepositoryNotRegisteredException(repositoryType);
    }

    // <editor-folding desc="BalletBox and Candidate Types" defaultstate="collapsed">
    static class BallotBox<C extends Candidate<I, ?>, I> {
        /// Repo API Interface -> List of candidates wanting to provide it
        private final Map<Class<? extends I>, TreeSet<C>> nominations = new HashMap<>();

        public void nominate(C candidate) {
            // The most specific API the new candidate claims to provide (the "Source").
            final var g = candidate.apiTypes().getFirst();
            for (var rt : candidate.apiTypes()) {
                var competitors = nominations.get(rt);
                if (competitors != null && !competitors.isEmpty()) {
                    // The most specific API of the current top candidate for this target.
                    var c = competitors.getFirst().apiTypes().getFirst();

                    // Unresolvable Ambiguity: The two source branches are unrelated.
                    // e.g., B extends A, C extends A. If both B and C are nominated, API A is ambiguous.
                    if (c != g && !c.isAssignableFrom(g) && !g.isAssignableFrom(c)) {
                        // TODO: add plugin names
                        throw new AmbiguousRepositoryApiException(String.format(
                                "Unresolvable ambiguity for %s! Two unrelated branches of the @RepositoryApi tree were nominated: %s and %s. " +
                                        "Inheritance must be linear; divergent branches serving the same base API are forbidden. " +
                                "Consider using a RepositoryComposition instead or moving the @RepositoryApi annotation.",
                                rt.getName(), c.getName(), g.getName()
                        ));
                    }
                }
            }
            for (var rt : candidate.apiTypes()) {
                nominations.computeIfAbsent(rt, k -> new TreeSet<>())
                        .add(candidate);
            }
        }

        // public Map<Class<? extends Repository>, T> runElection() {
        //     Map<Class<? extends Repository>, T> electionResult = new HashMap<>(nominations.size());
        //     return nominations.entrySet().stream()
        //             .map(e -> Map.entry(e.getKey(), e.getValue().getFirst()))
        //             .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // }

        public @Nullable C getWinner(Class<? extends I> repositoryType) {
            var candidates = nominations.get(repositoryType);
            if (candidates != null && !candidates.isEmpty()) {
                return candidates.getFirst();
            } else {
                return null;
            }
        }

        public boolean containsKey(@NotNull Class<? extends I> repositoryType) {
            return nominations.containsKey(repositoryType) && !nominations.get(repositoryType).isEmpty();
        }
    }

    private sealed static abstract class Candidate<I, J extends Candidate<I, ?>>
            implements Comparable<J>
            permits RepositoryCompositionCandidate, RepositoryImplementorCandidate, RepositoryProviderCandidate
    {
        final @Nullable PlatformHandle platformHandle;
        final int priority;
        final @NotNull List<Class<? extends I>> apiTypes;

        public Candidate(
                @Nullable PlatformHandle platformHandle,
                int priority,
                @NotNull List<Class<? extends I>> apiTypes
        ) {
            this.platformHandle = platformHandle;
            this.priority = priority;
            this.apiTypes = apiTypes;
        }

        public PlatformHandle platformHandle() {
            return platformHandle;
        }

        public int priority() {
            return priority;
        }

        public @NotNull List<Class<? extends I>> apiTypes() {
            return apiTypes;
        }

        @Override
        public int compareTo(@NotNull J other) {
            // Most specific repo api type wins (If A is-a B, A wins)
            final var lrt = this.apiTypes().getFirst();
            final var rrt = other.apiTypes().getFirst();
            if (lrt != rrt) return lrt.isAssignableFrom(rrt) ? 1 : -1;

            // Check Explicit Priority (Higher wins)
            int priorityComp = Integer.compare(other.priority(), this.priority());
            if (priorityComp != 0) return priorityComp;

            if (this.platformHandle != null && other.platformHandle != null) {
                // Check Dependency (If plugin B depends on A, B wins)
                int depComp = PlatformHandle.dependencyComparator(this.platformHandle, other.platformHandle);
                // Fallback to votingPlugin name for deterministic ties
                return depComp != 0 ? depComp : this.platformHandle.name().compareTo(other.platformHandle.name());
            }
            // If one or the other cast a vote then it wins
            if (this.platformHandle != null) return -1;
            if (other.platformHandle != null) return 1;
            // They're equal based on the information we have, override needs to decide
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Candidate<?, ?> other)) return false;
            return priority == other.priority
                    && Objects.equals(platformHandle, other.platformHandle)
                    && Objects.equals(apiTypes, other.apiTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(platformHandle, priority, apiTypes);
        }
    }

    public final static class RepositoryImplementorCandidate extends Candidate<Repository, RepositoryImplementorCandidate> {
        final @NotNull Class<? extends Repository> implementationType;

        public @NotNull Class<? extends Repository> implementationType() {
            return implementationType;
        }

        public RepositoryImplementorCandidate(
                @NotNull PlatformHandle platformHandle,
                int priority,
                @NotNull List<Class<? extends Repository>> apiTypes,
                @NotNull Class<? extends Repository> implementationType
        ) {
            super(platformHandle, priority, apiTypes);
            this.implementationType = implementationType;
        }

        /**
         * @param manager {@link SqlDatabaseManager} to forward to the created repository.
         */
        public Repository createInstance(
                @NotNull SqlDatabaseManager manager
        ) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
            Constructor<? extends Repository> constructor = implementationType.getConstructor(SqlDatabaseManager.class);
            // TODO: create the instance in manager.getPlugin()'s class-loader, if it's possible, so it takes
            //  ownership of the memory footprint and lifecycle.
            // manager.getPlugin().getClass().getClassLoader()
            return apiTypes.getFirst().cast(constructor.newInstance(manager));
        }

        @Override
        public int compareTo(@NotNull RepositoryImplementorCandidate other) {
            if (this == other || this.equals(other)) return 0;
            int k = super.compareTo(other);
            if (k != 0) return k;
            return this.implementationType.getName().compareTo(other.implementationType.getName());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RepositoryImplementorCandidate that)) return false;
            if (!super.equals(o)) return false;
            return Objects.equals(implementationType, that.implementationType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), implementationType);
        }
    }

    public final static class RepositoryProviderCandidate extends Candidate<Repository, RepositoryProviderCandidate> {
        static int nextPriority = 0;
        final @NotNull SqlDatabaseManager manager;

        public @NotNull SqlDatabaseManager manager() {
            return manager;
        }

        public RepositoryProviderCandidate(
                @NotNull SqlDatabaseManager manager,
                @NotNull List<Class<? extends Repository>> apiTypes
        ) {
            super(manager.getPlatformHandle(), nextPriority++, apiTypes);
            this.manager = manager;
        }

        @Override
        public int compareTo(@NotNull RepositoryProviderCandidate other) {
            if (this == other || this.equals(other)) return 0;
            int k = super.compareTo(other);
            if (k != 0) return k;
            return this.manager.getSqlConnectionConfig().connectionId().compareTo(other.manager.getSqlConnectionConfig().connectionId());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RepositoryProviderCandidate that)) return false;
            if (!super.equals(o)) return false;
            return Objects.equals(manager, that.manager);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), manager);
        }
    }

    public final static class RepositoryCompositionCandidate extends Candidate<RepositoryComposition, RepositoryCompositionCandidate> {
        static int nextPriority = 0;
        final @NotNull Class<? extends RepositoryComposition> compositionType;
        final @NotNull ThrowingFunction<@NotNull RepositoryRegistry, ? extends RepositoryComposition> creator;

        public @NotNull Class<? extends RepositoryComposition> compositionType() {
            return compositionType;
        }

        public @NotNull ThrowingFunction<@NotNull RepositoryRegistry, ? extends RepositoryComposition> creator() {
            return creator;
        }

        public RepositoryCompositionCandidate(
                @Nullable PlatformHandle platformHandle,
                @NotNull Class<? extends RepositoryComposition> compositionType,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, ? extends RepositoryComposition> creator
        ) {
            super(platformHandle, nextPriority++, apiTypesOf(compositionType));
            this.compositionType = compositionType;
            this.creator = creator;
        }

        @SuppressWarnings("unchecked")
        private static List<Class<? extends RepositoryComposition>> apiTypesOf(Class<? extends RepositoryComposition> compositionType) {
            // Scan up inheritance tree: gather every concrete parent class of this implementation
            List<Class<? extends RepositoryComposition>> apiTypes = new ArrayList<>();
            Class<?> current = compositionType;
            while (current != null && RepositoryComposition.class.isAssignableFrom(current)) {
                if (!current.isInterface() && !Modifier.isAbstract(current.getModifiers())) {
                    apiTypes.add((Class<? extends RepositoryComposition>) current);
                }
                current = current.getSuperclass();
            }
            return Collections.unmodifiableList(apiTypes);
        }

        @Override
        public int compareTo(@NotNull RepositoryCompositionCandidate other) {
            if (this == other || this.equals(other)) return 0;
            int k = super.compareTo(other);
            if (k != 0) return k;
            return this.compositionType.getName().compareTo(other.compositionType.getName());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RepositoryCompositionCandidate candidate)) return false;
            if (!super.equals(o)) return false;
            return Objects.equals(compositionType, candidate.compositionType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), compositionType);
        }
    }
    // </editor-folding>
}