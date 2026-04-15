package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.util.BubbleUpException;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.migration.MigrationLoader;
import io.github.ensgijs.dbm.migration.MigrationParseException;
import io.github.ensgijs.dbm.util.function.ThrowingBiFunction;
import io.github.ensgijs.dbm.util.function.ThrowingConsumer;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import io.github.ensgijs.dbm.util.io.ResourceScanner;
import io.github.ensgijs.dbm.util.objects.OneShotConsumableSubscribableEvent;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A service-locator registry that binds {@link Repository} API interfaces to provider
 * {@link SqlDatabaseManager} instances and implementation classes.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li><b>Scanning phase</b> – call {@link #register(PlatformHandle, ClassLoader)} (or its plugin-object
 *     overload) for each plugin/module during server startup.  This scans the classpath for
 *     {@code db/registry/} resources (filename&nbsp;= api FQCN, content&nbsp;= impl FQCN) and
 *     {@code db/migrations/} resources, then returns a {@link RegistrationHelper} for configuring
 *     lifecycle callbacks.</li>
 * <li><b>Configure phase</b> – the {@link RegistrationHelper#onConfigure(ThrowingConsumer)} callback
 *     is invoked in dependency order.  Use {@link RegistrationBootstrappingContext#publish} to
 *     register {@link SqlDatabaseManager} providers and
 *     {@link RegistrationBootstrappingContext#registerComposition} to register
 *     {@link RepositoryComposition} aggregators.</li>
 * <li><b>Ready phase</b> – call {@link #closeRegistration()} (or the overload accepting a
 *     {@code conflictResolver}) after everyone has had a chance to
 *     {@link #register(PlatformHandle, ClassLoader)}.  Provider conflicts from {@link PublishMode#CONTEST CONTEST}
 *     publications are resolved, then {@link RegistrationHelper#onReady(ThrowingConsumer)} callbacks
 *     are invoked, after which the registry is fully operational.</li>
 * </ol>
 *
 * <h2>Accessing repositories after registration</h2>
 * <ul>
 * <li>{@link #get(Class)} – retrieves the resolved repository instance; throws if not found.</li>
 * <li>{@link #find(Class)} – retrieves as {@link Optional}; empty if not published.</li>
 * <li>{@link #isProvidedBy(Class, SqlDatabaseManager)} – checks if a specific manager owns an api through this
 *     registry.</li>
 * </ul>
 *
 * <p>You may use the {@link #globalRegistry()} singleton or create isolated instances for testing or other use.</p>
 *
 * @see Repository
 * @see RepositoryApi
 * @see RepositoryComposition
 */
public final class RepositoryRegistry {
    public static final String DB_REGISTRY_RESOURCE_PATH = "db/registry/";

    private static RepositoryRegistry globalRegistryInstance;
    private static OneShotConsumableSubscribableEvent<RepositoryRegistry> onGlobalRegistryCreatedEvent;

    private final ResourceWalker resourceWalker;
    private volatile boolean registrationClosed = false;
    private Queue<RegistrationHelper> pendingRegistrations = new ConcurrentLinkedQueue<>();

    /// api class → impl class (populated from {@code db/registry/} resource files during scan)
    private final Map<Class<? extends Repository>, Class<? extends Repository>> implBindings = new HashMap<>();

    /// api class → list of ProviderEntries (populated via {@link #publish} during onConfigure)
    private final Map<Class<? extends Repository>, List<ProviderEntry>> contestantProviders = new LinkedHashMap<>();

    /// Resolved after {@link #closeRegistration}: api class → winning SqlDatabaseManager
    private volatile Map<Class<? extends Repository>, SqlDatabaseManager> resolvedProviders = null;

    /// Flyweight storage for composition instances: class → singleton
    private final Map<Class<? extends RepositoryComposition>, RepositoryComposition> compositeInstances = new HashMap<>();

    /// Direct (non-replaceable) composition creators: concrete class → creator function
    private final Map<Class<? extends RepositoryComposition>, ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition>> directCompositionCreators = new HashMap<>();

    /// Replaceable composition contests: abstract key → list of competitors
    private final Map<Class<? extends RepositoryComposition>, List<CompositionContestEntry>> compositionContests = new LinkedHashMap<>();

    /// Resolved after phase 1.75 in closeRegistration: lookup key → creator function
    private volatile Map<Class<? extends RepositoryComposition>, ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition>> resolvedCompositionCreators = null;

    // -----------------------------------------------------------------------
    // Singleton global registry
    // -----------------------------------------------------------------------

    /**
     * @return The singleton global {@link RepositoryRegistry} instance.
     * @see #isGlobalRegistryCreated()
     * @see #onGlobalRegistryCreatedEvent()
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

    /// Checks if the global registry has been created by someone having called {@link #globalRegistry()}.
    public static boolean isGlobalRegistryCreated() {
        return globalRegistryInstance != null;
    }

    /**
     * Allows subscribing to be notified when/if the global registry has been created. If the global
     * registry has already been created, any new subscribers will be immediately notified.
     */
    public static SubscribableEvent<RepositoryRegistry> onGlobalRegistryCreatedEvent() {
        return onGlobalRegistryCreatedEvent;
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    @FunctionalInterface
    @VisibleForTesting
    public interface ResourceWalker {
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
    public RepositoryRegistry(ResourceWalker resourceWalker) {
        this.resourceWalker = resourceWalker;
    }

    // -----------------------------------------------------------------------
    // State queries
    // -----------------------------------------------------------------------

    /** Returns {@code true} while {@link #register} may still be called. */
    public boolean isAcceptingRegistrations() {
        return !registrationClosed;
    }

    /** Returns {@code true} after {@link #closeRegistration()} completes. */
    public boolean isReady() {
        return registrationClosed;
    }

    // -----------------------------------------------------------------------
    // Provider publication
    // -----------------------------------------------------------------------

    /**
     * Defines whether a {@link #publish} call may be contested by another caller.
     */
    public enum PublishMode {
        /**
         * Only one provider may claim this api. A second {@code publish} for the same api throws
         * {@link RepositoryInitializationException} immediately.
         */
        EXCLUSIVE,
        /**
         * Multiple providers may contest for this api. The winner is determined by the
         * {@code conflictResolver} passed to {@link #closeRegistration(ThrowingBiFunction)}.
         * If {@code closeRegistration()} is called without a resolver and a contest exists,
         * it completes exceptionally.
         */
        CONTEST
    }

    /**
     * Carries a provider candidate and the platform handle that registered it.
     * Passed to the conflict resolver in {@link #closeRegistration}.
     */
    public record ProviderCandidate(
            @NotNull SqlDatabaseManager manager,
            @Nullable PlatformHandle registeredBy) {}

    /**
     * Carries a composition candidate and the platform handle that registered it.
     * Passed to the composition conflict resolver in {@link #closeRegistration}.
     */
    public record CompositionCandidate(
            @NotNull Class<? extends RepositoryComposition> concreteType,
            @Nullable PlatformHandle registeredBy) {}

    private record ProviderEntry(
            @NotNull SqlDatabaseManager manager,
            @NotNull PublishMode mode,
            @Nullable PlatformHandle registeredBy) {}

    private record CompositionContestEntry(
            Class<? extends RepositoryComposition> concreteType,
            ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition> creator,
            @Nullable PlatformHandle registeredBy) {}

    /**
     * Registers {@code manager} as the exclusive provider for {@code api}.
     * <p>Must be called from an {@link RegistrationHelper#onConfigure} callback.</p>
     *
     * @throws RepositoryInitializationException If another provider has already been published for this api.
     * @throws IllegalStateException             If registration has already been closed.
     */
    @VisibleForTesting
    <T extends Repository> void publish(
            @NotNull Class<T> api,
            @NotNull SqlDatabaseManager manager
    ) throws RepositoryInitializationException {
        publishInternal(api, manager, PublishMode.EXCLUSIVE, manager.getPlatformHandle());
    }

    /**
     * Registers {@code manager} as a provider for {@code api} with the specified {@code mode}.
     * <p>Must be called from an {@link RegistrationHelper#onConfigure} callback.</p>
     *
     * @throws RepositoryInitializationException If {@code mode} is {@link PublishMode#EXCLUSIVE} and another
     *                                           provider has already been published for this api, or if the
     *                                           existing publication was {@code EXCLUSIVE}.
     * @throws IllegalStateException             If registration has already been closed.
     */
    @VisibleForTesting
    synchronized <T extends Repository> void publish(
            @NotNull Class<T> api,
            @NotNull SqlDatabaseManager manager,
            @NotNull PublishMode mode
    ) throws RepositoryInitializationException {
        publishInternal(api, manager, mode, manager.getPlatformHandle());
    }

    private synchronized <T extends Repository> void publishInternal(
            @NotNull Class<T> api,
            @NotNull SqlDatabaseManager manager,
            @NotNull PublishMode mode,
            @Nullable PlatformHandle registeredBy) throws RepositoryInitializationException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Repository.validateRepositoryApi(api);  // throws if invalid

        var list = contestantProviders.computeIfAbsent(api, k -> new ArrayList<>());
        if (!list.isEmpty()) {
            var existing = list.getFirst();
            if (mode == PublishMode.EXCLUSIVE || existing.mode() == PublishMode.EXCLUSIVE) {
                throw new RepositoryInitializationException(
                        "Exclusive publish conflict for " + api.getName()
                                + ": already published by " + existing.manager().getSqlConnectionConfig().connectionId());
            }
        }
        list.add(new ProviderEntry(manager, mode, registeredBy));
    }

    /**
     * Programmatically overrides the impl binding for {@code api}, replacing any resource-file
     * declaration.  Silently replaces any existing binding.  Must be called before {@link #closeRegistration()}.
     *
     * @throws IllegalStateException If registration has already been closed.
     */
    public synchronized <T extends Repository> void bindImpl(
            @NotNull Class<T> api,
            @NotNull Class<? extends T> implClass
    ) {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Repository.validateRepositoryApi(api);  // throws if invalid
        implBindings.put(api, implClass);
    }

    // -----------------------------------------------------------------------
    // Repository access (post-registration)
    // -----------------------------------------------------------------------

    /**
     * Returns the resolved repository instance for {@code api}. The same instance will be returned every
     * time this method is called with the same parameter.
     *
     * @throws IllegalStateException          If {@link #isReady()} is {@code false}.
     * @throws RepositoryNotRegisteredException If no provider was published for this api.
     */
    @SuppressWarnings("unchecked")
    public <I extends Repository> @NotNull I get(@NotNull Class<I> api) {
        if (!registrationClosed) throw new IllegalStateException("Registration has not yet been closed!");
        var manager = resolvedProviders.get(api);
        if (manager == null) throw new RepositoryNotRegisteredException(api);
        var implClass = (Class<? extends I>) implBindings.get(api);
        if (implClass == null) throw new RepositoryNotRegisteredException(
                "No impl binding found for " + api.getName() + ". Declare one in db/registry/ or call bindImpl().");
        return manager.getRepository(api, implClass);
    }

    /**
     * Returns an {@link Optional} containing the resolved repository, or empty if not published.
     *
     * @throws IllegalStateException If {@link #isReady()} is {@code false}.
     */
    @SuppressWarnings("unchecked")
    public <I extends Repository> @NotNull Optional<I> find(@NotNull Class<I> api) {
        if (!registrationClosed) throw new IllegalStateException("Registration has not yet been closed!");
        var manager = resolvedProviders.get(api);
        var implClass = (Class<? extends I>) implBindings.get(api);
        if (manager == null || implClass == null) return Optional.empty();
        try {
            return Optional.of(manager.getRepository(api, implClass));
        } catch (RepositoryNotRegisteredException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if {@code manager} is the resolved provider for {@code api}.
     * {@code false} if !{@link #isReady()}.
     */
    public boolean isProvidedBy(@NotNull Class<? extends Repository> api, @NotNull SqlDatabaseManager manager) {
        if (!registrationClosed) return false;
        return manager == resolvedProviders.get(api);
    }

    /**
     * Returns the impl class bound to {@code api}. May be called while {@link #isAcceptingRegistrations()}
     * and {@link #isReady()}.
     *
     * @throws RepositoryNotRegisteredException If no impl binding was declared for this api.
     */
    public @NotNull Class<? extends Repository> getImplementationType(@NotNull Class<? extends Repository> api) {
        var impl = implBindings.get(api);
        if (impl == null) throw new RepositoryNotRegisteredException(
                "No impl binding found for " + api.getName() + ". Declare one in db/registry/ or call bindImpl().");
        return impl;
    }

    /**
     * Returns an {@link Optional} containing the impl class bound to {@code api}, or empty if no binding exists.
     * May be called while {@link #isAcceptingRegistrations()} and {@link #isReady()}.
     *
     * @throws RepositoryNotRegisteredException If no impl binding was declared for this api.
     */
    public @NotNull Optional<Class<? extends Repository>> findImplementationType(@NotNull Class<? extends Repository> api) {
        return Optional.ofNullable(implBindings.get(api));
    }

    // -----------------------------------------------------------------------
    // Registration lifecycle
    // -----------------------------------------------------------------------

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
    public RegistrationHelper register(@NotNull PlatformHandle platformHandle, @NotNull Object plugin)
            throws RepositoryInitializationException, MigrationParseException {
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
    public RegistrationHelper register(@NotNull PlatformHandle platformHandle, @NotNull ClassLoader scope)
            throws RepositoryInitializationException, MigrationParseException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        scanPlugin(platformHandle, scope);
        RegistrationHelper helper = new RegistrationHelper(platformHandle);
        pendingRegistrations.add(helper);
        return helper;
    }

    /**
     * Closes registration and runs lifecycle callbacks.  Equivalent to
     * {@link #closeRegistration(ThrowingBiFunction) closeRegistration(null)}.
     * <p>
     * If any {@link PublishMode#CONTEST CONTEST} conflicts exist and no resolver was supplied,
     * the returned future completes exceptionally.
     * </p>
     */
    public CompletableFuture<Boolean> closeRegistration() {
        return closeRegistration(null, null);
    }

    /**
     * Closes registration, resolves contested providers, and runs lifecycle callbacks.
     * Equivalent to {@link #closeRegistration(ThrowingBiFunction, ThrowingBiFunction)
     * closeRegistration(conflictResolver, null)}.
     *
     * @param conflictResolver Called for each contested provider api with the list of provider candidates.
     *                         May be {@code null} if no provider contests are expected.
     * @return A future that resolves to {@code true} on the first successful close, {@code false}
     *         on any subsequent call, or completes exceptionally on error.
     */
    public synchronized CompletableFuture<Boolean> closeRegistration(
            @Nullable ThrowingBiFunction<Class<? extends Repository>, List<ProviderCandidate>, SqlDatabaseManager> conflictResolver
    ) {
        return closeRegistration(conflictResolver, null);
    }

    /**
     * Closes registration, resolves contested providers and composition contests, and runs lifecycle callbacks.
     * Performs best-effort completeness, if any phase fails for any registrant no further
     * phases for that registrant will be executed. However, execution of other registrants
     * will proceed.
     *
     * <p>
     * Steps:
     * <ol>
     * <li>Marks registration as closed (returns {@code false} future if already closed).</li>
     * <li>Runs all {@link RegistrationHelper#onConfigure} callbacks in dependency order on a
     *     virtual thread.</li>
     * <li>Resolves contested providers using {@code conflictResolver}; if there are unresolved
     *     contests and no resolver was supplied the future completes exceptionally.</li>
     * <li>Resolves composition contests using {@code compositionConflictResolver}; merges direct
     *     registrations and contest winners into the resolved composition creator map.</li>
     * <li>Runs all {@link RegistrationHelper#onReady} callbacks in dependency order.</li>
     * </ol>
     *
     * @param conflictResolver Called for each contested provider api with the list of provider candidates.
     *                         Must return a non-null winner from that list, or {@code null} to
     *                         reject all (which causes the future to complete exceptionally).
     *                         May be {@code null} if no provider contests are expected.
     * @param compositionConflictResolver Called for each contested composition abstract key with the list
     *                         of competing composition candidates. Must return a non-null winner from that list,
     *                         or {@code null} to reject all (which causes the future to complete exceptionally).
     *                         May be {@code null} if no composition contests are expected.
     * @return A future that resolves to {@code true} on the first successful close, {@code false}
     *         on any subsequent call, or completes exceptionally on error.
     */
    @VisibleForTesting
    public synchronized CompletableFuture<Boolean> closeRegistration(
            @Nullable ThrowingBiFunction<Class<? extends Repository>, List<ProviderCandidate>, SqlDatabaseManager> conflictResolver,
            @Nullable ThrowingBiFunction<Class<? extends RepositoryComposition>, List<CompositionCandidate>, Class<? extends RepositoryComposition>> compositionConflictResolver
    ) {
        if (registrationClosed) return CompletableFuture.completedFuture(false);
        registrationClosed = true;

        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final List<RegistrationHelper> registrations = new LinkedList<>(this.pendingRegistrations);
        this.pendingRegistrations = null;
        registrations.sort(null);

        Thread.ofVirtual().start(() -> {
            RepositoryInitializationException err = null;

            // Phase 1: onConfigure (dependency order, already sorted)
            var iter = registrations.listIterator();
            while (iter.hasNext()) {
                var r = iter.next();
                r.mutable = false;
                if (r.onConfigure != null) {
                    try {
                        r.onConfigure.accept(new RegistrationBootstrappingContext(this, r.platformHandle));
                    } catch (Throwable ex) {
                        if (err == null) err = new RepositoryInitializationException("Error in onConfigure.");
                        err.addSuppressed(ex);
                        iter.remove(); // exclude from onReady since configure failed
                    }
                }
            }

            // Phase 1.5: Resolve contested providers
            Map<Class<? extends Repository>, SqlDatabaseManager> resolved = new HashMap<>();
            for (var entry : contestantProviders.entrySet()) {
                var contestants = entry.getValue();
                if (contestants.size() == 1) {
                    resolved.put(entry.getKey(), contestants.getFirst().manager());
                } else {
                    // Multiple CONTEST entries — need conflict resolution
                    if (conflictResolver == null) {
                        var e = new RepositoryInitializationException(
                                "Contest conflict for " + entry.getKey().getName()
                                        + " but no conflictResolver was provided to closeRegistration().");
                        if (err == null) err = e; else err.addSuppressed(e);
                    } else {
                        var candidates = contestants.stream()
                                .map(e -> new ProviderCandidate(e.manager(), e.registeredBy()))
                                .toList();
                        try {
                            SqlDatabaseManager winner = conflictResolver.apply(entry.getKey(), candidates);
                            var managers = candidates.stream().map(ProviderCandidate::manager).toList();
                            if (winner == null || !managers.contains(winner)) {
                                var e = new RepositoryInitializationException(
                                        "conflictResolver returned an invalid winner for " + entry.getKey().getName());
                                if (err == null) err = e; else err.addSuppressed(e);
                            } else {
                                resolved.put(entry.getKey(), winner);
                            }
                        } catch (Throwable ex) {
                            var e = new RepositoryInitializationException(
                                    "conflictResolver threw for " + entry.getKey().getName(), ex);
                            if (err == null) err = e; else err.addSuppressed(e);
                        }
                    }
                }
            }
            resolvedProviders = Collections.unmodifiableMap(resolved);

            // Phase 1.75: Resolve composition contests and build resolved creator map
            Map<Class<? extends RepositoryComposition>, ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition>> resolvedCreators = new HashMap<>();

            // Merge direct (non-replaceable) registrations
            resolvedCreators.putAll(directCompositionCreators);

            // Resolve replaceable (contest) registrations
            for (var entry : compositionContests.entrySet()) {
                var abstractKey = entry.getKey();
                var contestants = entry.getValue();

                if (contestants.size() == 1) {
                    resolvedCreators.put(abstractKey, contestants.getFirst().creator());
                } else {
                    // Multiple competitors — need conflict resolution
                    if (compositionConflictResolver == null) {
                        var concreteNames = contestants.stream()
                                .map(c -> c.concreteType().getName())
                                .collect(Collectors.joining(", "));
                        var e = new RepositoryInitializationException(
                                "Composition contest conflict for " + abstractKey.getName()
                                        + " (competitors: " + concreteNames + ")"
                                        + " but no compositionConflictResolver was provided to closeRegistration().");
                        if (err == null) err = e; else err.addSuppressed(e);
                    } else {
                        var candidateList = contestants.stream()
                                .map(c -> new CompositionCandidate(c.concreteType(), c.registeredBy()))
                                .toList();
                        var concreteTypes = candidateList.stream().map(CompositionCandidate::concreteType).toList();
                        try {
                            Class<? extends RepositoryComposition> winnerType =
                                    compositionConflictResolver.apply(abstractKey, candidateList);
                            if (winnerType == null || !concreteTypes.contains(winnerType)) {
                                var e = new RepositoryInitializationException(
                                        "compositionConflictResolver returned an invalid winner for " + abstractKey.getName());
                                if (err == null) err = e; else err.addSuppressed(e);
                            } else {
                                var winnerCreator = contestants.stream()
                                        .filter(c -> c.concreteType() == winnerType)
                                        .findFirst()
                                        .orElseThrow()
                                        .creator();
                                resolvedCreators.put(abstractKey, winnerCreator);
                            }
                        } catch (Throwable ex) {
                            var e = new RepositoryInitializationException(
                                    "compositionConflictResolver threw for " + abstractKey.getName(), ex);
                            if (err == null) err = e; else err.addSuppressed(e);
                        }
                    }
                }
            }

            resolvedCompositionCreators = Collections.unmodifiableMap(resolvedCreators);

            // Phase 2: onReady
            for (var r : registrations) {
                if (r.onReady != null) {
                    if (r.readyExecutor != null) {
                        // Async: errors are logged but cannot be propagated into the future as it may
                        //  have completed before the async handler has completed.
                        ThrowingConsumer<RepositoryRegistry> onReady = r.onReady;
                        r.readyExecutor.execute(() -> {
                            try {
                                onReady.accept(this);
                            } catch (Throwable ex) {
                                Logger.getLogger("RepositoryRegistry").log(
                                        Level.SEVERE,
                                        "async onReady threw for " + r.platformHandle.name(),
                                        ex);
                            }
                        });
                    } else {
                        try {
                            r.onReady.accept(this);
                        } catch (Throwable ex) {
                            if (err == null) err = new RepositoryInitializationException("Error in onReady.");
                            err.addSuppressed(ex);
                        }
                    }
                }
            }

            if (err != null) future.completeExceptionally(err);
            else future.complete(true);
        });

        return future;
    }

    // -----------------------------------------------------------------------
    // Plugin scanning
    // -----------------------------------------------------------------------

    /**
     * Scans a plugin's classpath for repository registry resources and migrations.
     * <p>
     * Resource files are expected at {@code db/registry/}, where:
     * <ul>
     * <li><b>filename</b> is the fully-qualified name of the {@link RepositoryApi}-annotated interface</li>
     * <li><b>file content</b> is the fully-qualified name of the concrete implementation class</li>
     * </ul>
     * </p>
     *
     * @param platformHandle The plugin identifier and dependency checker.
     * @param scope          The class loader used to locate jar resources.
     * @throws MigrationParseException           If an error occurred while loading db migration resources.
     * @throws RepositoryInitializationException If an I/O error occurs or classes cannot be resolved.
     * @throws IllegalStateException             If called after {@link #closeRegistration()} has been invoked.
     */
    @VisibleForTesting
    void scanPlugin(@NotNull PlatformHandle platformHandle, @NotNull ClassLoader scope)
            throws RepositoryInitializationException, MigrationParseException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");

        MigrationLoader.loadMigrations(platformHandle, scope);

        // Binding conflicts are collected so all resource files are processed before reporting.
        // Hard errors (missing class, bad content, invalid contract) still propagate immediately.
        List<RepositoryInitializationException> bindingConflicts = new ArrayList<>();
        try {
            resourceWalker.visit(scope, DB_REGISTRY_RESOURCE_PATH, entry -> {
                String apiName = entry.path().substring(DB_REGISTRY_RESOURCE_PATH.length());
                String implName;
                try (var reader = entry.asReader()) {
                    implName = reader.lines().map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse(null);
                }
                if (implName == null) {
                    throw new RepositoryInitializationException(
                            "Registry resource file '" + apiName + "' has no content. "
                                    + "File content must be the fully-qualified impl class name.");
                }

                Class<? extends Repository> apiClass = Class.forName(apiName, false, scope)
                        .asSubclass(Repository.class);
                Class<? extends Repository> implClass = Class.forName(implName, false, scope)
                        .asSubclass(apiClass);

                // Validate that the impl properly adheres to the Repository contract.
                // We already know implClass is-a apiClass.
                Repository.identifyRepositoryApi(implClass); // throws if not valid

                synchronized (this) {
                    var existing = implBindings.get(apiClass);
                    if (existing != null && existing != implClass) {
                        // Accumulate conflicts rather than throwing so all entries are still scanned.
                        bindingConflicts.add(new RepositoryInitializationException(
                                "Conflicting impl bindings for " + apiName
                                        + ": existing=" + existing.getName() + ", new=" + implName
                                        + ". Resolve by having only one plugin declare this binding,"
                                        + " or call bindImpl() explicitly."));
                    } else {
                        implBindings.put(apiClass, implClass);
                    }
                }
            });
        } catch (Exception ex) {
            throw new RepositoryInitializationException(
                    "Error while scanning plugin jar resources: " + platformHandle.name(), BubbleUpException.unwrap(ex));
        }

        if (!bindingConflicts.isEmpty()) {
            var combined = new RepositoryInitializationException(
                    bindingConflicts.size() + " impl binding conflict(s) found while scanning " + platformHandle.name() + ".");
            bindingConflicts.forEach(combined::addSuppressed);
            throw combined;
        }
    }

    // -----------------------------------------------------------------------
    // Repository Composition
    // -----------------------------------------------------------------------

    /**
     * Registers a {@link RepositoryComposition} implementation with an auto-discovered constructor.
     * The class must have either a {@code (RepositoryRegistry)} or no-arg constructor.
     * <p>
     * Two valid lineages are supported:
     * <ol>
     * <li>{@code Concretion implements RepositoryComposition} — direct, non-replaceable. Stored under
     *     the concrete key in {@code directCompositionCreators}.</li>
     * <li>{@code Concretion extends AbstractBase implements RepositoryComposition} — replaceable.
     *     Stored as a competitor in {@code compositionContests} under the abstract key.</li>
     * </ol>
     * <p>Must be called before {@link #closeRegistration()}.</p>
     * <p>Note: {@code compositionType} must be a concrete (non-abstract, non-interface) class.
     * Abstract types are not accepted here; they are the result of validating a registered
     * concretion's lineage, not an input to registration.</p>
     * @throws IllegalArgumentException If {@code compositionType} is abstract or an interface, if it extends
     *     a concrete {@link RepositoryComposition}, or if the abstract intermediary chain is deeper than one.
     * @apiNote Check {@link #isAcceptingRegistrations()} before calling outside of
     * {@link #register(PlatformHandle, Object)}'s {@code onConfigure} callback.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType
    ) {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        registerCompositionInternal(compositionType, discoverCompositionCreator(compositionType), null);
    }

    /**
     * Registers a {@link RepositoryComposition} implementation with a custom creator function.
     * <p>
     * Two valid lineages are supported:
     * <ol>
     * <li>{@code Concretion implements RepositoryComposition} — direct, non-replaceable.</li>
     * <li>{@code Concretion extends AbstractBase implements RepositoryComposition} — replaceable.</li>
     * </ol>
     * <p>Must be called before {@link #closeRegistration()}.</p>
     * <p>Note: {@code compositionType} must be a concrete (non-abstract, non-interface) class.
     * Abstract types are not accepted here; they are the result of validating a registered
     * concretion's lineage, not an input to registration.</p>
     * @throws IllegalArgumentException If {@code compositionType} is abstract or an interface, if it extends
     *     a concrete {@link RepositoryComposition}, or if the abstract intermediary chain is deeper than one.
     * @apiNote Check {@link #isAcceptingRegistrations()} before calling outside of
     * {@link #register(PlatformHandle, Object)}'s {@code onConfigure} callback.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType,
            @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
    ) {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        registerCompositionInternal(compositionType, creator, null);
    }

    /**
     * Determines the lineage of {@code compositionType} using {@link RepositoryComposition#identifyAbstractKey}
     * and routes it to either {@code directCompositionCreators} (lineage 1) or {@code compositionContests} (lineage 2).
     */
    @SuppressWarnings("unchecked")
    private <T extends RepositoryComposition> void registerCompositionInternal(
            Class<T> compositionType,
            ThrowingFunction<RepositoryRegistry, ? extends T> creator,
            @Nullable PlatformHandle registeredBy
    ) {
        // Use the static helper — both validates AND identifies lineage
        Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
        if (abstractKey != null) {
            // Lineage 2: replaceable — append to contest under the abstract key
            compositionContests
                    .computeIfAbsent(abstractKey, k -> new ArrayList<>())
                    .add(new CompositionContestEntry(compositionType, creator, registeredBy));
        } else {
            // Lineage 1: direct — store under the concrete key (last-write wins)
            directCompositionCreators.put(compositionType, creator);
        }
    }

    /**
     * Retrieves the flyweight instance of a composition.
     *
     * <p>After {@link #closeRegistration()} completes:</p>
     * <ul>
     * <li>If {@code compositionType} is a concrete type registered as a replaceable competitor
     *     (not the abstract key), throws {@link RepositoryInitializationException} naming the
     *     abstract key to use instead.</li>
     * <li>If {@code compositionType} is an abstract/interface type not in the resolved map,
     *     throws {@link RepositoryInitializationException}.</li>
     * <li>Otherwise, returns the flyweight via the resolved creator. Auto-discovery still works
     *     for direct concrete types that were not explicitly registered.</li>
     * </ul>
     *
     * @throws IllegalStateException              If called before registration is closed or while
     *                                            {@code closeRegistration()} is still in progress.
     * @throws RepositoryInitializationException  If the type is an invalid lookup key, is not found,
     *                                            or if instantiation or initialization fails.
     */
    public <T extends RepositoryComposition> @NotNull T getCompositeRepository(
            @NotNull Class<T> compositionType
    ) throws RepositoryInitializationException {
        return getCompositeRepository(compositionType, null);
    }

    /**
     * Retrieves the flyweight instance of a composition, with an optional fallback creator.
     *
     * <ul>
     * <li>If {@code compositionType} is in {@code resolvedCompositionCreators}, the resolved creator
     *     is used and {@code fallbackCreator} is ignored.</li>
     * <li>If {@code compositionType} is a concrete type whose lineage identifies it as a replaceable
     *     competitor (i.e. has an abstract key), throws {@link RepositoryInitializationException}
     *     naming the abstract key to use instead.</li>
     * <li>Otherwise, uses {@code fallbackCreator} if provided. This is the late-binding path for
     *     abstract slots. If {@code fallbackCreator} is {@code null} and auto-discovery is not
     *     applicable, throws {@link RepositoryInitializationException}.</li>
     * </ul>
     *
     * @throws IllegalStateException              If called before registration is closed or while
     *                                            {@code closeRegistration()} is still in progress.
     * @throws RepositoryInitializationException  If the type is an invalid lookup key, is not found,
     *                                            or if instantiation or initialization fails.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends RepositoryComposition> @NotNull T getCompositeRepository(
            @NotNull Class<T> compositionType,
            @Nullable ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> fallbackCreator
    ) throws RepositoryInitializationException {
        if (!registrationClosed) throw new IllegalStateException("Cannot access composite flyweights before registration is closed.");
        if (resolvedCompositionCreators == null) throw new IllegalStateException("closeRegistration() is still in progress.");

        RepositoryComposition cached = compositeInstances.get(compositionType);
        if (cached != null) return (T) cached;

        ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition> creator =
                resolvedCompositionCreators.get(compositionType);

        if (creator != null) {
            // Resolved creator wins; fallbackCreator is ignored.
        } else if (fallbackCreator != null) {
            // Late-binding fallback path.
            creator = fallbackCreator;
        } else {
            // Auto-discover on first access for direct concrete types not explicitly registered.
            if (compositionType.isInterface() || Modifier.isAbstract(compositionType.getModifiers())) {
                throw new RepositoryInitializationException(
                        "No resolved creator found for abstract/interface composition type " + compositionType.getName()
                                + ". Ensure it is registered and that closeRegistration() has completed.");
            }
            // For concrete types not in the resolved map: check lineage via the contract helper
            try {
                Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
                if (abstractKey != null) {
                    throw new RepositoryInitializationException(
                            compositionType.getName() + " is a concretion of replaceable abstract " + abstractKey.getName()
                            + ". Use " + abstractKey.getName()
                            + " as the lookup type and pass a custom creator such as MyImpl::new instead.");
                }
            } catch (IllegalArgumentException e) {
                throw new RepositoryInitializationException(
                        compositionType.getName() + " has an invalid composition lineage: " + e.getMessage(), e);
            }
            creator = discoverCompositionCreator(compositionType);
        }

        T instance;
        try {
            instance = (T) Objects.requireNonNull(creator.apply(this));
        } catch (Throwable ex) {
            throw new RepositoryInitializationException("Error while creating instance of " + compositionType.getName(), ex);
        }

        // Register the flyweight under the lookup key before onInitialize so that circular
        // references between compositions resolve correctly.
        compositeInstances.put(compositionType, instance);

        try {
            instance.onInitialize(this);
        } catch (Throwable ex) {
            compositeInstances.remove(compositionType, instance);
            throw new RepositoryInitializationException("Error while initializing " + compositionType.getName(), ex);
        }

        return instance;
    }

    private <T extends RepositoryComposition> @NotNull ThrowingFunction<RepositoryRegistry, T> discoverCompositionCreator(
            @NotNull Class<T> compositionType
    ) {
        Constructor<T> ctor1 = null;
        Constructor<T> ctor0 = null;
        try {
            ctor1 = compositionType.getConstructor(RepositoryRegistry.class);
            ctor1.setAccessible(true);
        } catch (Exception ignored) {}

        if (ctor1 == null) {
            try {
                ctor0 = compositionType.getDeclaredConstructor();
                ctor0.setAccessible(true);
            } catch (Exception ignored) {}
        }

        if (ctor1 == null && ctor0 == null) {
            throw new IllegalArgumentException("Composite " + compositionType.getName()
                    + " must have a constructor taking only RepositoryRegistry or a no-arg constructor"
                    + " if no creator is provided.");
        }

        final Constructor<T> finalCtor1 = ctor1;
        final Constructor<T> finalCtor0 = ctor0;
        return reg -> finalCtor1 != null ? finalCtor1.newInstance(reg) : finalCtor0.newInstance();
    }

    // -----------------------------------------------------------------------
    // Default conflict resolvers
    // -----------------------------------------------------------------------

    /**
     * Default provider conflict resolver based on {@link PlatformHandle} dependency order.
     * Returns the manager registered by the platform that is most downstream (i.e., depends on all
     * other registrants directly). Returns {@code null} if no unique downstream winner exists
     * (indicating a true conflict that requires user-supplied resolution).
     */
    public static @Nullable SqlDatabaseManager defaultProviderConflictResolver(
            @NotNull Class<? extends Repository> api,
            @NotNull List<ProviderCandidate> candidates) {
        return resolveByDependencyOrder(candidates, ProviderCandidate::registeredBy, ProviderCandidate::manager);
    }

    /**
     * Default composition conflict resolver based on {@link PlatformHandle} dependency order.
     * Returns the concrete type registered by the platform that is most downstream. Returns
     * {@code null} if no unique downstream winner exists.
     */
    public static @Nullable Class<? extends RepositoryComposition> defaultCompositionConflictResolver(
            @NotNull Class<? extends RepositoryComposition> abstractKey,
            @NotNull List<CompositionCandidate> candidates) {
        return resolveByDependencyOrder(candidates, CompositionCandidate::registeredBy, CompositionCandidate::concreteType);
    }

    private static <T, R> @Nullable R resolveByDependencyOrder(
            @NotNull List<T> candidates,
            @NotNull Function<T, @Nullable PlatformHandle> handleExtractor,
            @NotNull Function<T, R> resultExtractor) {
        if (candidates.size() == 1) return resultExtractor.apply(candidates.getFirst());
        candidates:
        for (T candidate : candidates) {
            PlatformHandle ph = handleExtractor.apply(candidate);
            if (ph == null) continue;
            for (T other : candidates) {
                if (other == candidate) continue;
                PlatformHandle otherPh = handleExtractor.apply(other);
                if (otherPh == null || !ph.dependsOn(otherPh)) continue candidates;
            }
            return resultExtractor.apply(candidate);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // RegistrationHelper
    // -----------------------------------------------------------------------

    public static final class RegistrationHelper implements Comparable<RegistrationHelper> {
        private static final AtomicInteger ORDINAL_PROVIDER = new AtomicInteger();
        private final int ordinal;
        private final @NotNull PlatformHandle platformHandle;
        private @Nullable ThrowingConsumer<RegistrationBootstrappingContext> onConfigure;
        private @Nullable ThrowingConsumer<RepositoryRegistry> onReady;
        private @Nullable Executor readyExecutor;
        private boolean mutable = true;

        private RegistrationHelper(@NotNull PlatformHandle platformHandle) {
            this.ordinal = ORDINAL_PROVIDER.incrementAndGet();
            this.platformHandle = platformHandle;
        }

        /**
         * Sets the callback invoked during the configure phase.
         * <p>
         * Use the supplied {@link RegistrationBootstrappingContext} to {@link RegistrationBootstrappingContext#publish publish}
         * providers, {@link RegistrationBootstrappingContext#bindImpl bind} impl classes, and
         * {@link RegistrationBootstrappingContext#registerComposition register} compositions.
         * This callback is guaranteed to run before any {@link #onReady} callbacks.
         * </p>
         */
        public RegistrationHelper onConfigure(ThrowingConsumer<RegistrationBootstrappingContext> op) {
            if (!mutable) throw new IllegalStateException("RegistrationHelper is no longer mutable.");
            this.onConfigure = op;
            return this;
        }

        /**
         * Sets the callback invoked during the ready phase.
         * <p>
         * All providers have been resolved at this point; use the supplied {@link RepositoryRegistry}
         * to access repositories via {@link RepositoryRegistry#get} and compositions via
         * {@link RepositoryRegistry#getCompositeRepository}. This callback runs after all
         * {@link #onConfigure} callbacks have completed.
         * </p>
         */
        public RegistrationHelper onReady(ThrowingConsumer<RepositoryRegistry> op) {
            if (!mutable) throw new IllegalStateException("RegistrationHelper is no longer mutable.");
            this.onReady = op;
            return this;
        }

        /**
         * Sets the {@link Executor} used to run the {@link #onReady} callback.
         * Useful when the ready callback performs heavy initialization that should run
         * off the main registration thread (e.g., a virtual-thread executor).
         */
        public RegistrationHelper setReadyExecutor(@NotNull Executor executor) {
            if (!mutable) throw new IllegalStateException("RegistrationHelper is no longer mutable.");
            this.readyExecutor = executor;
            return this;
        }

        @Override
        public int compareTo(@NotNull RegistrationHelper that) {
            if (this == that) return 0;
            int depComp = PlatformHandle.dependencyComparator(this.platformHandle, that.platformHandle);
            return depComp != 0 ? depComp : Integer.compare(this.ordinal, that.ordinal);
        }
    }

    // -----------------------------------------------------------------------
    // RegistrationBootstrappingContext
    // -----------------------------------------------------------------------

    public static final class RegistrationBootstrappingContext {
        private final @NotNull RepositoryRegistry registry;
        private final @NotNull PlatformHandle platformHandle;

        private RegistrationBootstrappingContext(@NotNull RepositoryRegistry registry, @NotNull PlatformHandle platformHandle) {
            this.registry = registry;
            this.platformHandle = platformHandle;
        }

        public @NotNull RepositoryRegistry registry() { return registry; }
        public @NotNull PlatformHandle platformHandle() { return platformHandle; }

        /**
         * Registers {@code manager} as the exclusive provider for {@code api}.
         * @see RepositoryRegistry#publish(Class, SqlDatabaseManager)
         */
        public <T extends Repository> void publish(@NotNull Class<T> api, @NotNull SqlDatabaseManager manager)
                throws RepositoryInitializationException {
            registry.publishInternal(api, manager, PublishMode.EXCLUSIVE, platformHandle);
        }

        /**
         * Registers {@code manager} as a provider for {@code api} with the specified mode.
         * @see RepositoryRegistry#publish(Class, SqlDatabaseManager, PublishMode)
         */
        public <T extends Repository> void publish(@NotNull Class<T> api, @NotNull SqlDatabaseManager manager,
                @NotNull PublishMode mode) throws RepositoryInitializationException {
            registry.publishInternal(api, manager, mode, platformHandle);
        }

        /**
         * Programmatically overrides the impl binding for {@code api}.
         * @see RepositoryRegistry#bindImpl(Class, Class)
         */
        public <T extends Repository> void bindImpl(@NotNull Class<T> api, @NotNull Class<? extends T> implClass) {
            registry.bindImpl(api, implClass);
        }

        /**
         * Registers a {@link RepositoryComposition} with an auto-discovered constructor.
         * @see RepositoryRegistry#registerComposition(Class)
         */
        public <T extends RepositoryComposition> void registerComposition(@NotNull Class<T> compositionType) {
            registry.registerCompositionInternal(
                    compositionType, registry.discoverCompositionCreator(compositionType), platformHandle);
        }

        /**
         * Registers a {@link RepositoryComposition} with a custom creator function.
         * @see RepositoryRegistry#registerComposition(Class, ThrowingFunction)
         */
        public <T extends RepositoryComposition> void registerComposition(
                @NotNull Class<T> compositionType,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
        ) {
            registry.registerCompositionInternal(compositionType, creator, platformHandle);
        }
    }
}
