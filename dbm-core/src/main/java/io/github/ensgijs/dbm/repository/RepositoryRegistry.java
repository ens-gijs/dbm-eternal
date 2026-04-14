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
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <li><b>Ready phase</b> – call {@link #closeRegistration(ThrowingBiFunction)} after everyone has had a chance to
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

    /// Registered composition creators: concrete class → creator function
    private final Map<Class<? extends RepositoryComposition>, ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition>> compositionCreators = new HashMap<>();

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

    private record ProviderEntry(@NotNull SqlDatabaseManager manager, @NotNull PublishMode mode) {}

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
        publish(api, manager, PublishMode.EXCLUSIVE);
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
        list.add(new ProviderEntry(manager, mode));
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
        return closeRegistration(null);
    }

    /**
     * Closes registration, resolves contested providers, and runs lifecycle callbacks.
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
     * <li>Runs all {@link RegistrationHelper#onReady} callbacks in dependency order.</li>
     * </ol>
     *
     * @param conflictResolver Called for each contested api with the list of provider managers.
     *                         Must return a non-null winner from that list, or {@code null} to
     *                         reject all (which causes the future to complete exceptionally).
     *                         May be {@code null} if no contests are expected.
     * @return A future that resolves to {@code true} on the first successful close, {@code false}
     *         on any subsequent call, or completes exceptionally on error.
     */
    @VisibleForTesting
    public synchronized CompletableFuture<Boolean> closeRegistration(
            @Nullable ThrowingBiFunction<Class<? extends Repository>, List<SqlDatabaseManager>, SqlDatabaseManager> conflictResolver
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
                        var managers = contestants.stream().map(ProviderEntry::manager).toList();
                        try {
                            var winner = conflictResolver.apply(entry.getKey(), managers);
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
                    // TODO: evaluate if this logic is stable or not; how does it behave when 3 or more plugins
                    //  conflict; give platform an opportunity to step in; don't fail to process other walked
                    //  assets because one failed like this
                    if (existing != null && existing != implClass) {
                        throw new RepositoryInitializationException(
                                "Conflicting impl bindings for " + apiName
                                        + ": existing=" + existing.getName() + ", new=" + implName
                                        + ". Resolve by having only one plugin declare this binding, or call bindImpl() explicitly.");
                    }
                    implBindings.put(apiClass, implClass);
                }
            });
        } catch (Exception ex) {
            throw new RepositoryInitializationException(
                    "Error while scanning plugin jar resources: " + platformHandle.name(), BubbleUpException.unwrap(ex));
        }
    }

    // -----------------------------------------------------------------------
    // Repository Composition
    // -----------------------------------------------------------------------

    /**
     * Registers a {@link RepositoryComposition} implementation with an auto-discovered constructor.
     * The class must have either a {@code (RepositoryRegistry)} or no-arg constructor.
     * <p>Must be called before {@link #closeRegistration()}.</p>
     * @apiNote Check {@link #isAcceptingRegistrations()} before calling outside of
     * {@link #register(PlatformHandle, Object)}'s {@code onConfigure} callback.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType
    ) {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        registerCompositionInternal(compositionType, discoverCompositionCreator(compositionType));
    }

    /**
     * Registers a {@link RepositoryComposition} implementation with a custom creator function.
     * <p>Must be called before {@link #closeRegistration()}.</p>
     * @apiNote Check {@link #isAcceptingRegistrations()} before calling outside of
     * {@link #register(PlatformHandle, Object)}'s {@code onConfigure} callback.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType,
            @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
    ) {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        registerCompositionInternal(compositionType, creator);
    }

    /// May be called at any time, not subject to registration window.
    @SuppressWarnings("unchecked")
    private <T extends RepositoryComposition> void registerCompositionInternal(
            Class<T> compositionType,
            ThrowingFunction<RepositoryRegistry, ? extends T> creator
    ) {
        if (compositionType.isInterface() || Modifier.isAbstract(compositionType.getModifiers())) {
            throw new IllegalArgumentException("compositionType must be a non-interface, non-abstract class.");
        }
        // Register for the full concrete hierarchy (most-specific wins via last-write)
        // TODO: "most-specific wins via last-write" is dangerous outside of isAcceptingRegistrations()
        Class<?> current = compositionType;
        while (current != null && RepositoryComposition.class.isAssignableFrom(current)) {
            // TODO: be sure this behavior is documented
            if (!current.isInterface() && !Modifier.isAbstract(current.getModifiers())) {
                compositionCreators.put((Class<? extends RepositoryComposition>) current, creator);
            }
            current = current.getSuperclass();
        }
    }

    /**
     * Retrieves the flyweight instance of a composition.
     *
     * @throws IllegalStateException              If called before registration is closed.
     * @throws RepositoryInitializationException  If instantiation or initialization fails.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends RepositoryComposition> @NotNull T getCompositeRepository(
            @NotNull Class<T> compositionType
    ) throws RepositoryInitializationException {
        if (!registrationClosed) throw new IllegalStateException("Cannot access composite flyweights before registration is closed.");
        RepositoryComposition cached = compositeInstances.get(compositionType);
        if (cached != null) return (T) cached;

        var creator = compositionCreators.get(compositionType);
        if (creator == null) {
            // Auto-register on first access
            var typedCreator = discoverCompositionCreator(compositionType);
            registerCompositionInternal(compositionType, typedCreator);
            creator = typedCreator;
        }

        T instance;
        try {
            instance = (T) Objects.requireNonNull(creator.apply(this));
        } catch (Throwable ex) {
            throw new RepositoryInitializationException("Error while creating instance of " + compositionType.getName(), ex);
        }

        // Pre-populate flyweight map for the full concrete hierarchy before onInitialize,
        // so circular references resolve correctly.
        Class<?> current = compositionType;
        while (current != null && RepositoryComposition.class.isAssignableFrom(current)) {
            if (!current.isInterface() && !Modifier.isAbstract(current.getModifiers())) {
                // TODO: this is dangerous, may clobber when late-binding
                compositeInstances.put((Class<? extends RepositoryComposition>) current, instance);
            }
            current = current.getSuperclass();
        }

        try {
            instance.onInitialize(this);
        } catch (Throwable ex) {
            // Roll back flyweight registrations on failure
            current = compositionType;
            while (current != null && RepositoryComposition.class.isAssignableFrom(current)) {
                // TODO: unsafe assumption about binding ownership
                compositeInstances.remove(current, instance);
                current = current.getSuperclass();
            }
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

        // TODO: improve docs
        /** Sets the callback invoked during the configure phase (provider publication). */
        public RegistrationHelper onConfigure(ThrowingConsumer<RegistrationBootstrappingContext> op) {
            if (!mutable) throw new IllegalStateException("RegistrationHelper is no longer mutable.");
            this.onConfigure = op;
            return this;
        }

        // TODO: improve docs
        /** Sets the callback invoked during the ready phase (repository and composition access). */
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
            registry.publish(api, manager);
        }

        /**
         * Registers {@code manager} as a provider for {@code api} with the specified mode.
         * @see RepositoryRegistry#publish(Class, SqlDatabaseManager, PublishMode)
         */
        public <T extends Repository> void publish(@NotNull Class<T> api, @NotNull SqlDatabaseManager manager,
                @NotNull PublishMode mode) throws RepositoryInitializationException {
            registry.publish(api, manager, mode);
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
            registry.registerComposition(compositionType);
        }

        /**
         * Registers a {@link RepositoryComposition} with a custom creator function.
         * @see RepositoryRegistry#registerComposition(Class, ThrowingFunction)
         */
        public <T extends RepositoryComposition> void registerComposition(
                @NotNull Class<T> compositionType,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
        ) {
            registry.registerComposition(compositionType, creator);
        }
    }
}
