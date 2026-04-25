package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.util.BubbleUpException;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.sql.SqlDialect;
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
 * <li><b>Ready phase</b> – call {@link #closeRegistration()} (or the overload accepting
 *     {@link RegistrationOptions}) after everyone has had a chance to
 *     {@link #register(PlatformHandle, ClassLoader)}.  Provider conflicts from
 *     {@link ConflictMode#CONTEST CONTEST} publications are resolved, then
 *     {@link RegistrationHelper#onReady(ThrowingConsumer)} callbacks are invoked, after which the
 *     registry is fully operational.</li>
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

    /// api class → impl class.
    /// Pre-close: only holds EXCLUSIVE programmatic bindings (for early introspection).
    /// Post-close: holds all resolved winners (populated during closeRegistration).
    private final Map<Class<? extends Repository>, Class<? extends Repository>> implBindings = new HashMap<>();

    /// All binding candidates: api → ordered list of candidates (resource-file SUGGEST + programmatic)
    private final Map<Class<? extends Repository>, List<BindingEntry>> bindingCandidates = new LinkedHashMap<>();

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
     * Controls how a registration competes when multiple candidates exist for the same slot.
     * <p>
     * Used for provider publication ({@link #publish}), impl binding ({@link #bindImpl}),
     * and replaceable composition registration ({@link #registerComposition}).
     * </p>
     */
    public enum ConflictMode {
        /**
         * Implicit for resource-file binding declarations. Automatically yields to
         * {@link #CONTEST} or {@link #EXCLUSIVE} entries; only enters conflict resolution
         * when all remaining candidates are also {@code SUGGEST}.
         */
        SUGGEST,
        /**
         * Explicit competition. Automatically yields to {@link #EXCLUSIVE} entries; only 
         * enters conflict resolution when all remaining candidates are also {@code CONTEST}.
         */
        CONTEST,
        /**
         * Only one registration allowed. A second {@code EXCLUSIVE} for the same slot throws
         * {@link RepositoryInitializationException} immediately at registration time.
         * {@code EXCLUSIVE} displaces any {@code SUGGEST} or {@code CONTEST} candidates silently
         * (with a warning logged for displaced {@code CONTEST} entries).
         */
        EXCLUSIVE
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

    /**
     * Carries an impl-binding candidate and the platform handle that registered it.
     * Passed to the binding conflict resolver in {@link RegistrationOptions}.
     */
    public record BindingCandidate(
            @NotNull Class<? extends Repository> implClass,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy) {}

    private record ProviderEntry(
            @NotNull SqlDatabaseManager manager,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy) {}

    private record CompositionContestEntry(
            Class<? extends RepositoryComposition> concreteType,
            ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition> creator,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy) {}

    private record BindingEntry(
            @NotNull Class<? extends Repository> implClass,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy) {}

    /**
     * Registers {@code manager} as the {@link ConflictMode#EXCLUSIVE EXCLUSIVE} provider for {@code api}.
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
        publishInternal(api, manager, ConflictMode.EXCLUSIVE, manager.getPlatformHandle());
    }

    /**
     * Registers {@code manager} as a provider for {@code api} with the specified {@code mode}.
     * <p>Must be called from an {@link RegistrationHelper#onConfigure} callback.</p>
     *
     * @throws RepositoryInitializationException If {@code mode} is {@link ConflictMode#EXCLUSIVE EXCLUSIVE} and another
     *                                           provider has already been published for this api, or if the
     *                                           existing publication was {@code EXCLUSIVE}.
     * @throws IllegalStateException             If registration has already been closed.
     */
    @VisibleForTesting
    synchronized <T extends Repository> void publish(
            @NotNull Class<T> api,
            @NotNull SqlDatabaseManager manager,
            @NotNull ConflictMode mode
    ) throws RepositoryInitializationException {
        publishInternal(api, manager, mode, manager.getPlatformHandle());
    }

    private synchronized <T extends Repository> void publishInternal(
            @NotNull Class<T> api,
            @NotNull SqlDatabaseManager manager,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy) throws RepositoryInitializationException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Repository.validateRepositoryApi(api);  // throws if invalid

        var list = contestantProviders.computeIfAbsent(api, k -> new ArrayList<>());

        // If an EXCLUSIVE already holds this slot nothing more can be added.
        var existingExclusive = list.stream().filter(e -> e.mode() == ConflictMode.EXCLUSIVE).findFirst();
        if (existingExclusive.isPresent()) {
            throw new RepositoryInitializationException(
                    "Exclusive publish conflict for " + api.getName()
                            + ": already published exclusively by "
                            + existingExclusive.get().manager().getSqlConnectionConfig().connectionId());
        }

        // EXCLUSIVE displaces SUGGEST entries at close time, but cannot coexist with CONTEST.
        if (mode == ConflictMode.EXCLUSIVE && list.stream().anyMatch(e -> e.mode() == ConflictMode.CONTEST)) {
            throw new RepositoryInitializationException(
                    "Exclusive publish conflict for " + api.getName()
                            + ": cannot publish EXCLUSIVE when CONTEST publication(s) already exist.");
        }

        list.add(new ProviderEntry(manager, mode, registeredBy));
    }

    /**
     * Programmatically binds {@code implClass} as the {@link ConflictMode#EXCLUSIVE EXCLUSIVE}
     * provider for {@code api}. Throws if another EXCLUSIVE binding already exists for this api.
     * Silently displaces any prior {@link ConflictMode#SUGGEST SUGGEST} entries.
     *
     * @throws RepositoryInitializationException If another EXCLUSIVE binding already exists.
     * @throws IllegalStateException             If registration has already been closed.
     */
    public synchronized <T extends Repository> void bindImpl(
            @NotNull Class<T> api,
            @NotNull Class<? extends T> implClass
    ) throws RepositoryInitializationException {
        bindImplInternal(api, implClass, ConflictMode.EXCLUSIVE, null);
    }

    /**
     * Programmatically binds {@code implClass} as a provider for {@code api} with the given mode.
     *
     * @throws RepositoryInitializationException If {@code mode} is {@link ConflictMode#EXCLUSIVE} and
     *                                           another EXCLUSIVE binding already exists for this api.
     * @throws IllegalStateException             If registration has already been closed.
     */
    public synchronized <T extends Repository> void bindImpl(
            @NotNull Class<T> api,
            @NotNull Class<? extends T> implClass,
            @NotNull ConflictMode mode
    ) throws RepositoryInitializationException {
        bindImplInternal(api, implClass, mode, null);
    }

    private synchronized <T extends Repository> void bindImplInternal(
            @NotNull Class<T> api,
            @NotNull Class<? extends T> implClass,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy
    ) throws RepositoryInitializationException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Repository.validateRepositoryApi(api);
        var list = bindingCandidates.computeIfAbsent(api, k -> new ArrayList<>());
        if (mode == ConflictMode.EXCLUSIVE) {
            var existingExclusive = list.stream()
                    .filter(e -> e.mode() == ConflictMode.EXCLUSIVE)
                    .findFirst();
            if (existingExclusive.isPresent()) {
                throw new RepositoryInitializationException(
                        "Exclusive binding conflict for " + api.getName()
                                + ": already bound to " + existingExclusive.get().implClass().getName()
                                + ", cannot also bind to " + implClass.getName() + ".");
            }
            // EXCLUSIVE immediately updates implBindings for pre-close introspection
            implBindings.put(api, implClass);
        }
        list.add(new BindingEntry(implClass, mode, registeredBy));
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
     * Closes registration and runs lifecycle callbacks. No conflict resolvers are active;
     * any unresolved conflict causes the returned future to complete exceptionally.
     * Equivalent to {@link #closeRegistration(RegistrationOptions) closeRegistration(RegistrationOptions.empty())}.
     */
    public CompletableFuture<Boolean> closeRegistration() {
        return closeRegistration(RegistrationOptions.empty());
    }

    /**
     * Closes registration, resolves conflicts, and runs lifecycle callbacks.
     *
     * <p>Steps:
     * <ol>
     * <li>Marks registration closed (returns {@code false} future if already closed).</li>
     * <li><b>Phase 1</b> — Runs all {@link RegistrationHelper#onConfigure} callbacks in dependency order.
     *     Any handle whose callback throws is <em>blackmarked</em>: all of its in-progress registrations
     *     are purged before the resolution phases begin. By default the error is fatal; set
     *     {@link RegistrationOptions#continueOnConfigureError(boolean) continueOnConfigureError(true)}
     *     to demote it to a warning.</li>
     * <li><b>Phase 2a</b> — Resolves contested provider slots. {@link ConflictMode#SUGGEST SUGGEST} yields
     *     automatically to {@link ConflictMode#CONTEST CONTEST}; multiple same-mode entries go to the
     *     {@link RegistrationOptions#providerResolver(ThrowingBiFunction) providerResolver}.</li>
     * <li><b>Phase 2b</b> — Resolves contested composition slots with the same SUGGEST-yields logic.</li>
     * <li><b>Phase 2c</b> — Resolves impl-binding conflicts: {@link ConflictMode#EXCLUSIVE EXCLUSIVE} always
     *     wins (displacing {@link ConflictMode#SUGGEST SUGGEST} silently and {@link ConflictMode#CONTEST CONTEST}
     *     with a warning); {@link ConflictMode#SUGGEST SUGGEST} yields to {@link ConflictMode#CONTEST CONTEST};
     *     multiple same-mode entries go to the
     *     {@link RegistrationOptions#bindingResolver(ThrowingBiFunction) bindingResolver}.</li>
     * <li><b>Phase 3</b> — Runs all {@link RegistrationHelper#onReady} callbacks in dependency order.</li>
     * </ol>
     *
     * @param options Resolvers and options for conflict resolution. Use {@link RegistrationOptions#withDefaults()}
     *                to apply the dependency-order defaults, or {@link RegistrationOptions#empty()} if no
     *                conflicts are expected.
     * @return A future that resolves to {@code true} on the first successful close, {@code false}
     *         on any subsequent call, or completes exceptionally on error.
     */
    @VisibleForTesting
    public synchronized CompletableFuture<Boolean> closeRegistration(@NotNull RegistrationOptions options) {
        if (registrationClosed) return CompletableFuture.completedFuture(false);
        registrationClosed = true;

        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final List<RegistrationHelper> registrations = new LinkedList<>(this.pendingRegistrations);
        this.pendingRegistrations = null;
        registrations.sort(null);

        Thread.ofVirtual().start(() -> {
            RepositoryInitializationException err = null;

            // Phase 1: onConfigure (dependency order, already sorted)
            Set<PlatformHandle> blackmarkedHandles = new HashSet<>();
            var iter = registrations.listIterator();
            while (iter.hasNext()) {
                var r = iter.next();
                r.mutable = false;
                if (r.onConfigure != null) {
                    try {
                        r.onConfigure.accept(new RegistrationBootstrappingContext(this, r.platformHandle));
                    } catch (Throwable ex) {
                        blackmarkedHandles.add(r.platformHandle);
                        if (err == null) err = new RepositoryInitializationException(
                                "Error in onConfigure for '" + r.platformHandle.name() + "'.");
                        err.addSuppressed(ex);
                        iter.remove(); // exclude from onReady since configure failed
                    }
                }
            }

            // Purge all registrations made by errored handles; their configurations are incomplete
            // and partial state may be dangerous.
            if (!blackmarkedHandles.isEmpty()) {
                contestantProviders.forEach((api, list) -> list.removeIf(e -> blackmarkedHandles.contains(e.registeredBy())));
                bindingCandidates.forEach((api, list) -> list.removeIf(e -> blackmarkedHandles.contains(e.registeredBy())));
                compositionContests.forEach((key, list) -> list.removeIf(e -> blackmarkedHandles.contains(e.registeredBy())));
                // Note: directCompositionCreators does not track registeredBy; entries from errored handles cannot be purged.
                if (options.continueOnConfigureError) {
                    // Non-fatal: log a warning and clear the error so the future can still complete successfully.
                    Logger.getLogger("RepositoryRegistry").warning(
                            blackmarkedHandles.size() + " platform handle(s) failed onConfigure and their registrations "
                                    + "were discarded: "
                                    + blackmarkedHandles.stream().map(PlatformHandle::name).collect(Collectors.joining(", ")));
                    err = null;
                }
            }

            // Phase 2a: Resolve contested providers
            Map<Class<? extends Repository>, SqlDatabaseManager> resolved = new HashMap<>();
            for (var entry : contestantProviders.entrySet()) {
                var contestants = entry.getValue();
                if (contestants.size() == 1) {
                    resolved.put(entry.getKey(), contestants.getFirst().manager());
                } else {
                    // SUGGEST yields to CONTEST or EXCLUSIVE automatically.
                    var explicit = contestants.stream()
                            .filter(e -> e.mode() != ConflictMode.SUGGEST)
                            .toList();
                    var toResolve = explicit.isEmpty() ? contestants : explicit;

                    if (toResolve.size() == 1) {
                        resolved.put(entry.getKey(), toResolve.getFirst().manager());
                    } else if (options.providerResolver == null) {
                        var e = new RepositoryInitializationException(
                                "Provider contest conflict for " + entry.getKey().getName()
                                        + " but no providerResolver was provided in RegistrationOptions.");
                        if (err == null) err = e; else err.addSuppressed(e);
                    } else {
                        var candidates = toResolve.stream()
                                .map(e -> new ProviderCandidate(e.manager(), e.registeredBy()))
                                .toList();
                        try {
                            SqlDatabaseManager winner = options.providerResolver.apply(entry.getKey(), candidates);
                            var managers = candidates.stream().map(ProviderCandidate::manager).toList();
                            if (winner == null || !managers.contains(winner)) {
                                var e = new RepositoryInitializationException(
                                        "providerResolver returned an invalid winner for " + entry.getKey().getName());
                                if (err == null) err = e; else err.addSuppressed(e);
                            } else {
                                resolved.put(entry.getKey(), winner);
                            }
                        } catch (Throwable ex) {
                            var e = new RepositoryInitializationException(
                                    "providerResolver threw for " + entry.getKey().getName(), ex);
                            if (err == null) err = e; else err.addSuppressed(e);
                        }
                    }
                }
            }
            resolvedProviders = Collections.unmodifiableMap(resolved);

            // Phase 2b: Resolve composition contests and build resolved creator map
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
                    // Filter out SUGGEST if any CONTEST exists
                    var explicitContests = contestants.stream()
                            .filter(c -> c.mode() == ConflictMode.CONTEST)
                            .toList();
                    var toResolve = explicitContests.isEmpty() ? contestants : explicitContests;

                    if (toResolve.size() == 1) {
                        resolvedCreators.put(abstractKey, toResolve.getFirst().creator());
                    } else if (options.compositionResolver == null) {
                        var concreteNames = toResolve.stream()
                                .map(c -> c.concreteType().getName())
                                .collect(Collectors.joining(", "));
                        var e = new RepositoryInitializationException(
                                "Composition contest conflict for " + abstractKey.getName()
                                        + " (competitors: " + concreteNames + ")"
                                        + " but no compositionResolver was provided in RegistrationOptions.");
                        if (err == null) err = e; else err.addSuppressed(e);
                    } else {
                        var candidateList = toResolve.stream()
                                .map(c -> new CompositionCandidate(c.concreteType(), c.registeredBy()))
                                .toList();
                        var concreteTypes = candidateList.stream().map(CompositionCandidate::concreteType).toList();
                        try {
                            Class<? extends RepositoryComposition> winnerType =
                                    options.compositionResolver.apply(abstractKey, candidateList);
                            if (winnerType == null || !concreteTypes.contains(winnerType)) {
                                var e = new RepositoryInitializationException(
                                        "compositionResolver returned an invalid winner for " + abstractKey.getName());
                                if (err == null) err = e; else err.addSuppressed(e);
                            } else {
                                var winnerCreator = toResolve.stream()
                                        .filter(c -> c.concreteType() == winnerType)
                                        .findFirst().orElseThrow().creator();
                                resolvedCreators.put(abstractKey, winnerCreator);
                            }
                        } catch (Throwable ex) {
                            var e = new RepositoryInitializationException(
                                    "compositionResolver threw for " + abstractKey.getName(), ex);
                            if (err == null) err = e; else err.addSuppressed(e);
                        }
                    }
                }
            }

            resolvedCompositionCreators = Collections.unmodifiableMap(resolvedCreators);

            // Phase 2c: Resolve impl binding conflicts
            for (var entry : bindingCandidates.entrySet()) {
                var api = entry.getKey();
                var candidates = entry.getValue();

                // Dialect filter: if a provider was resolved for this api and has a known dialect,
                // filter candidates to those that declare support for that dialect.
                {
                    SqlDatabaseManager provider = resolvedProviders.get(api);
                    if (provider != null) {
                        SqlDialect D = provider.activeDialect();
                        if (D != null && D != SqlDialect.UNDEFINED) {
                            var filtered = new ArrayList<BindingEntry>();
                            var dropped = new ArrayList<BindingEntry>();
                            for (var c : candidates) {
                                boolean supported;
                                try {
                                    supported = Repository.supportsDialect(c.implClass(), D);
                                } catch (IllegalStateException ex) {
                                    // Invalid @RepositoryImpl is always a configuration bug — warn and treat as unsupported.
                                    Logger.getLogger("RepositoryRegistry").warning(
                                            "Skipping impl " + c.implClass().getName() + " for "
                                                    + api.getName() + " due to invalid @RepositoryImpl: "
                                                    + ex.getMessage());
                                    supported = false;
                                }
                                if (supported) filtered.add(c); else dropped.add(c);
                            }
                            if (!dropped.isEmpty() && options.logDialectFilterDrops) {
                                String droppedNames = dropped.stream()
                                        .map(c -> c.implClass().getSimpleName()
                                                + " " + dialectsLabel(c.implClass()))
                                        .collect(Collectors.joining(", "));
                                Logger.getLogger("RepositoryRegistry").warning(
                                        "Dialect filter for " + api.getSimpleName() + " (active: " + D
                                                + ") dropped: " + droppedNames);
                            }
                            // Check EXCLUSIVE-dropped before filtered.isEmpty() so the flag applies
                            // even when the EXCLUSIVE was the sole candidate.
                            if (dropped.stream().anyMatch(c -> c.mode() == ConflictMode.EXCLUSIVE)) {
                                implBindings.remove(api);
                                if (options.throwOnExclusiveDialectFilterDrop) {
                                    var e = new RepositoryInitializationException(
                                            "EXCLUSIVE binding for " + api.getName()
                                                    + " was eliminated by dialect filter (does not support " + D + ").");
                                    if (err == null) err = e; else err.addSuppressed(e);
                                } else {
                                    Logger.getLogger("RepositoryRegistry").warning(
                                            "EXCLUSIVE binding for " + api.getName()
                                                    + " was eliminated by dialect filter (does not support "
                                                    + D + "); suppressed.");
                                }
                                continue;
                            }
                            if (filtered.isEmpty()) {
                                var e = new RepositoryInitializationException(
                                        "No impl for " + api.getName() + " supports dialect " + D
                                                + "; all " + dropped.size()
                                                + " candidate(s) were eliminated by dialect filter.");
                                if (err == null) err = e; else err.addSuppressed(e);
                                implBindings.remove(api);
                                continue;
                            }
                            candidates = filtered;
                        }
                    }
                }

                // Check for EXCLUSIVE (at most one; enforced at add time)
                var exclusives = candidates.stream()
                        .filter(e -> e.mode() == ConflictMode.EXCLUSIVE).toList();
                if (!exclusives.isEmpty()) {
                    // EXCLUSIVE wins; implBindings was already set at bindImpl call time.
                    // Warn if any CONTEST entries were displaced.
                    long displacedContest = candidates.stream()
                            .filter(e -> e.mode() == ConflictMode.CONTEST).count();
                    if (displacedContest > 0) {
                        Logger.getLogger("RepositoryRegistry").warning(
                                "EXCLUSIVE binding for " + api.getName() + " displaced "
                                        + displacedContest + " CONTEST candidate(s).");
                    }
                    continue; // implBindings already has the winner
                }

                // No EXCLUSIVE: filter out SUGGEST if any CONTEST exists
                var contests = candidates.stream()
                        .filter(e -> e.mode() == ConflictMode.CONTEST).toList();
                var toResolve = contests.isEmpty() ? candidates : contests;

                if (toResolve.size() == 1) {
                    implBindings.put(api, toResolve.getFirst().implClass());
                    continue;
                }

                // Multiple candidates — need resolver
                if (options.bindingResolver == null) {
                    var implNames = toResolve.stream()
                            .map(e -> e.implClass().getName())
                            .collect(Collectors.joining(", "));
                    var e = new RepositoryInitializationException(
                            "Binding conflict for " + api.getName()
                                    + " (candidates: " + implNames + ")"
                                    + " but no bindingResolver was provided in RegistrationOptions.");
                    if (err == null) err = e; else err.addSuppressed(e);
                    continue;
                }

                var candidateList = toResolve.stream()
                        .map(e -> new BindingCandidate(e.implClass(), e.mode(), e.registeredBy()))
                        .toList();
                var implClasses = candidateList.stream().map(BindingCandidate::implClass).toList();
                try {
                    Class<? extends Repository> winner = options.bindingResolver.apply(api, candidateList);
                    if (winner == null || !implClasses.contains(winner)) {
                        var e = new RepositoryInitializationException(
                                "bindingResolver returned an invalid winner for " + api.getName());
                        if (err == null) err = e; else err.addSuppressed(e);
                    } else {
                        implBindings.put(api, winner);
                    }
                } catch (Throwable ex) {
                    var e = new RepositoryInitializationException(
                            "bindingResolver threw for " + api.getName(), ex);
                    if (err == null) err = e; else err.addSuppressed(e);
                }
            }

            // Subscribe to each distinct provider's dialect-change event so that
            // setSqlConnectionConfig can reject dialect swaps incompatible with registry-bound impls.
            resolvedProviders.values().stream().distinct()
                    .forEach(mgr -> {
                        var evt = mgr.onBeforeDialectChangeEvent();
                        if (evt != null) evt.subscribe(dialect -> validateDialectChange(mgr, dialect));
                    });

            // Phase 3: onReady
            for (var r : registrations) {
                if (r.onReady != null) {
                    if (r.readyExecutor != null) {
                        // Async: errors are logged but cannot be propagated into the future as it may
                        // not have completed before the async handler has completed.
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
                    bindingCandidates
                            .computeIfAbsent(apiClass, k -> new ArrayList<>())
                            .add(new BindingEntry(implClass, ConflictMode.SUGGEST, platformHandle));
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
        registerCompositionInternal(compositionType, discoverCompositionCreator(compositionType), ConflictMode.CONTEST, null);
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
        registerCompositionInternal(compositionType, creator, ConflictMode.CONTEST, null);
    }

    /**
     * Registers a replaceable {@link RepositoryComposition} implementation with the given conflict mode.
     * <p>Only valid for replaceable (abstract-extending) lineage. Calling this on a direct-concrete
     * composition type throws {@link IllegalArgumentException}.</p>
     *
     * @throws IllegalArgumentException If {@code compositionType} has direct lineage (not replaceable),
     *     is abstract or an interface, or if the abstract intermediary chain is invalid.
     * @throws RepositoryInitializationException If {@code mode} is {@link ConflictMode#EXCLUSIVE} and
     *     another composition is already registered for the same abstract key.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType,
            @NotNull ConflictMode mode
    ) throws RepositoryInitializationException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
        if (abstractKey == null) {
            throw new IllegalArgumentException(
                    "ConflictMode use is only applicable to replaceable (abstract-extending) composition types. "
                            + compositionType.getName() + " is a direct composition type and does not support ConflictMode.");
        }
        registerCompositionInternal(compositionType, discoverCompositionCreator(compositionType), mode, null);
    }

    /**
     * Registers a replaceable {@link RepositoryComposition} with a custom creator and explicit mode.
     *
     * @throws IllegalArgumentException If {@code compositionType} has direct lineage.
     * @throws RepositoryInitializationException If {@code mode} is {@link ConflictMode#EXCLUSIVE} and
     *     another composition is already registered for the same abstract key.
     */
    public synchronized <T extends RepositoryComposition> void registerComposition(
            @NotNull Class<T> compositionType,
            @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator,
            @NotNull ConflictMode mode
    ) throws RepositoryInitializationException {
        if (registrationClosed) throw new IllegalStateException("Registration is closed!");
        Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
        if (abstractKey == null) {
            throw new IllegalArgumentException(
                    "ConflictMode use is only applicable to replaceable (abstract-extending) composition types. "
                            + compositionType.getName() + " is a direct composition type and does not support ConflictMode.");
        }
        registerCompositionInternal(compositionType, creator, mode, null);
    }

    /**
     * Determines the lineage of {@code compositionType} using {@link RepositoryComposition#identifyAbstractKey}
     * and routes it to either {@code directCompositionCreators} (lineage 1) or {@code compositionContests} (lineage 2).
     */
    @SuppressWarnings("unchecked")
    private <T extends RepositoryComposition> void registerCompositionInternal(
            Class<T> compositionType,
            ThrowingFunction<RepositoryRegistry, ? extends T> creator,
            @NotNull ConflictMode mode,
            @Nullable PlatformHandle registeredBy
    ) throws RepositoryInitializationException {
        // Use the static helper — both validates AND identifies lineage
        Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
        if (abstractKey != null) {
            // Lineage 2 replaceable
            var list = compositionContests.computeIfAbsent(abstractKey, k -> new ArrayList<>());
            if (mode == ConflictMode.EXCLUSIVE && !list.isEmpty()) {
                throw new RepositoryInitializationException(
                        "Exclusive composition conflict for " + abstractKey.getName()
                                + ": already has competitor " + list.getFirst().concreteType().getName()
                                + ", cannot also register " + compositionType.getName() + " as EXCLUSIVE.");
            }
            list.add(new CompositionContestEntry(compositionType, creator, mode, registeredBy));
        } else {
            // Lineage 1 direct: mode is not applicable, store under the concrete key (last-write wins)
            @SuppressWarnings("unchecked")
            var previous = directCompositionCreators.put(
                    compositionType, (ThrowingFunction<RepositoryRegistry, ? extends RepositoryComposition>) creator);
            if (previous != null) {
                Logger.getLogger("RepositoryRegistry").warning(
                        "Direct composition " + compositionType.getName()
                                + " was registered more than once; the previous creator has been replaced."
                                + " This may indicate a duplicate or conflicting registration.");
            }
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

    /**
     * Default impl-binding conflict resolver based on {@link PlatformHandle} dependency order.
     * Returns the impl class registered by the most downstream platform. Returns {@code null}
     * if no unique downstream winner exists.
     */
    public static @Nullable Class<? extends Repository> defaultBindingConflictResolver(
            @NotNull Class<? extends Repository> api,
            @NotNull List<BindingCandidate> candidates) {
        return resolveByDependencyOrder(candidates, BindingCandidate::registeredBy, BindingCandidate::implClass);
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
    // Dialect change validation
    // -----------------------------------------------------------------------

    /**
     * Validates that changing {@code manager}'s active dialect to {@code newDialect} does not violate
     * any resolved impl bindings. Called by the {@link io.github.ensgijs.dbm.sql.SqlClient#onBeforeDialectChangeEvent()}
     * subscriber registered during {@link #closeRegistration()}.
     *
     * @throws IllegalStateException If any resolved impl bound to {@code manager} does not support
     *                               {@code newDialect}.
     */
    void validateDialectChange(@NotNull SqlDatabaseManager manager, @NotNull SqlDialect newDialect) {
        if (!registrationClosed || resolvedProviders == null) return;
        List<String> errors = new ArrayList<>();
        for (var entry : resolvedProviders.entrySet()) {
            if (entry.getValue() != manager) continue;
            var impl = implBindings.get(entry.getKey());
            if (impl == null) continue;
            try {
                if (!Repository.supportsDialect(impl, newDialect)) {
                    errors.add(entry.getKey().getSimpleName() + " -> " + impl.getSimpleName()
                            + " (supports: " + Repository.supportedDialectsOf(impl) + ")");
                }
            } catch (IllegalStateException ex) {
                errors.add(entry.getKey().getSimpleName() + ": " + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot change dialect to " + newDialect
                            + ": the following repository impls do not support it: "
                            + String.join(", ", errors));
        }
    }

    private static String dialectsLabel(Class<?> implClass) {
        try {
            return Repository.supportedDialectsOf(implClass).toString();
        } catch (IllegalStateException e) {
            return "[missing @RepositoryImpl]";
        }
    }

    // -----------------------------------------------------------------------
    // RegistrationOptions
    // -----------------------------------------------------------------------

    /**
     * Configuration for {@link #closeRegistration(RegistrationOptions)}.
     * <p>
     * Use {@link #withDefaults()} to pre-populate all resolvers with the dependency-order
     * default, or {@link #empty()} (equivalent to calling {@link #closeRegistration()}) to
     * treat any unresolved conflict as a fatal error.
     * </p>
     */
    public static final class RegistrationOptions {

        @Nullable ThrowingBiFunction<Class<? extends Repository>, List<ProviderCandidate>, SqlDatabaseManager> providerResolver;
        @Nullable ThrowingBiFunction<Class<? extends RepositoryComposition>, List<CompositionCandidate>, Class<? extends RepositoryComposition>> compositionResolver;
        @Nullable ThrowingBiFunction<Class<? extends Repository>, List<BindingCandidate>, Class<? extends Repository>> bindingResolver;
        boolean continueOnConfigureError = false;
        boolean logDialectFilterDrops = true;
        boolean throwOnExclusiveDialectFilterDrop = true;

        private RegistrationOptions() {}

        /**
         * Returns a {@code RegistrationOptions} with all three resolvers pre-set to the
         * dependency-order defaults ({@link #defaultProviderConflictResolver},
         * {@link #defaultCompositionConflictResolver}, {@link #defaultBindingConflictResolver}).
         */
        public static RegistrationOptions withDefaults() {
            RegistrationOptions opts = new RegistrationOptions();
            opts.providerResolver = RepositoryRegistry::defaultProviderConflictResolver;
            opts.compositionResolver = RepositoryRegistry::defaultCompositionConflictResolver;
            opts.bindingResolver = RepositoryRegistry::defaultBindingConflictResolver;
            return opts;
        }

        /**
         * Returns a {@code RegistrationOptions} with all resolvers {@code null}.
         * Any conflict causes the close future to complete exceptionally.
         * Equivalent to calling {@link #closeRegistration()} directly.
         */
        public static RegistrationOptions empty() {
            return new RegistrationOptions();
        }

        /** Sets the resolver for contested provider slots. */
        public RegistrationOptions providerResolver(
                @Nullable ThrowingBiFunction<Class<? extends Repository>, List<ProviderCandidate>, SqlDatabaseManager> resolver) {
            this.providerResolver = resolver;
            return this;
        }

        /** Sets the resolver for contested composition slots. */
        public RegistrationOptions compositionResolver(
                @Nullable ThrowingBiFunction<Class<? extends RepositoryComposition>, List<CompositionCandidate>, Class<? extends RepositoryComposition>> resolver) {
            this.compositionResolver = resolver;
            return this;
        }

        /** Sets the resolver for contested impl-binding slots. */
        public RegistrationOptions bindingResolver(
                @Nullable ThrowingBiFunction<Class<? extends Repository>, List<BindingCandidate>, Class<? extends Repository>> resolver) {
            this.bindingResolver = resolver;
            return this;
        }

        /**
         * Controls whether a WARNING is logged when impl candidates are eliminated by the dialect
         * filter during {@link RepositoryRegistry#closeRegistration()}.
         * Default: {@code true}.
         */
        public RegistrationOptions logDialectFilterDrops(boolean log) {
            this.logDialectFilterDrops = log;
            return this;
        }

        /**
         * Controls whether dropping a {@link ConflictMode#EXCLUSIVE EXCLUSIVE} impl binding due to
         * the dialect filter is treated as a fatal error ({@code true}, default) or logged as a
         * warning ({@code false}).
         */
        public RegistrationOptions throwOnExclusiveDialectFilterDrop(boolean throwOnDrop) {
            this.throwOnExclusiveDialectFilterDrop = throwOnDrop;
            return this;
        }

        /**
         * Controls what happens when a platform handle's {@code onConfigure} callback throws.
         * <p>
         * When {@code true}: the errored handle is blackmarked, all its in-progress registrations
         * are discarded, a warning is logged, and the close future can still complete successfully
         * (assuming no other errors). When {@code false} (the default): the same discarding and
         * blackmarking occurs, but the error is propagated and the future completes exceptionally.
         * </p>
         *
         * @param continueOnError {@code true} to treat configure failures as non-fatal; {@code false}
         *                        (default) to treat them as fatal.
         */
        public RegistrationOptions continueOnConfigureError(boolean continueOnError) {
            this.continueOnConfigureError = continueOnError;
            return this;
        }
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
            registry.publishInternal(api, manager, ConflictMode.EXCLUSIVE, platformHandle);
        }

        /**
         * Registers {@code manager} as a provider for {@code api} with the specified mode.
         * @see RepositoryRegistry#publish(Class, SqlDatabaseManager, ConflictMode)
         */
        public <T extends Repository> void publish(@NotNull Class<T> api, @NotNull SqlDatabaseManager manager,
                @NotNull ConflictMode mode) throws RepositoryInitializationException {
            registry.publishInternal(api, manager, mode, platformHandle);
        }

        /**
         * Programmatically binds {@code implClass} as the EXCLUSIVE provider for {@code api}.
         * @see RepositoryRegistry#bindImpl(Class, Class)
         */
        public <T extends Repository> void bindImpl(@NotNull Class<T> api, @NotNull Class<? extends T> implClass)
                throws RepositoryInitializationException {
            registry.bindImplInternal(api, implClass, ConflictMode.EXCLUSIVE, platformHandle);
        }

        /**
         * Programmatically binds {@code implClass} as a provider for {@code api} with the given mode.
         * @see RepositoryRegistry#bindImpl(Class, Class, ConflictMode)
         */
        public <T extends Repository> void bindImpl(@NotNull Class<T> api, @NotNull Class<? extends T> implClass,
                @NotNull ConflictMode mode) throws RepositoryInitializationException {
            registry.bindImplInternal(api, implClass, mode, platformHandle);
        }

        /**
         * Registers a {@link RepositoryComposition} with an auto-discovered constructor.
         * @see RepositoryRegistry#registerComposition(Class)
         */
        public <T extends RepositoryComposition> void registerComposition(@NotNull Class<T> compositionType)
                throws RepositoryInitializationException {
            registry.registerCompositionInternal(
                    compositionType, registry.discoverCompositionCreator(compositionType), ConflictMode.CONTEST, platformHandle);
        }

        /**
         * Registers a {@link RepositoryComposition} with a custom creator function.
         * @see RepositoryRegistry#registerComposition(Class, ThrowingFunction)
         */
        public <T extends RepositoryComposition> void registerComposition(
                @NotNull Class<T> compositionType,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator
        ) throws RepositoryInitializationException {
            registry.registerCompositionInternal(compositionType, creator, ConflictMode.CONTEST, platformHandle);
        }

        /**
         * Registers a replaceable {@link RepositoryComposition} with an auto-discovered constructor and the given mode.
         */
        public <T extends RepositoryComposition> void registerComposition(
                @NotNull Class<T> compositionType, @NotNull ConflictMode mode)
                throws RepositoryInitializationException {
            Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
            if (abstractKey == null) throw new IllegalArgumentException(
                    "ConflictMode is only applicable to replaceable (abstract-extending) composition types. "
                            + compositionType.getName() + " is a direct composition type.");
            registry.registerCompositionInternal(
                    compositionType, registry.discoverCompositionCreator(compositionType), mode, platformHandle);
        }

        /**
         * Registers a replaceable {@link RepositoryComposition} with a custom creator and the given mode.
         */
        public <T extends RepositoryComposition> void registerComposition(
                @NotNull Class<T> compositionType,
                @NotNull ThrowingFunction<@NotNull RepositoryRegistry, @NotNull T> creator,
                @NotNull ConflictMode mode)
                throws RepositoryInitializationException {
            Class<? extends RepositoryComposition> abstractKey = RepositoryComposition.identifyAbstractKey(compositionType);
            if (abstractKey == null) throw new IllegalArgumentException(
                    "ConflictMode is only applicable to replaceable (abstract-extending) composition types. "
                            + compositionType.getName() + " is a direct composition type.");
            registry.registerCompositionInternal(compositionType, creator, mode, platformHandle);
        }
    }
}
