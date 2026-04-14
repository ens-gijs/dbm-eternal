package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.util.objects.ValueOrException;
import io.github.ensgijs.dbm.migration.SchemaMigrator;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryComposition;
import io.github.ensgijs.dbm.repository.RepositoryInitializationException;
import io.github.ensgijs.dbm.repository.RepositoryRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds upon {@link SqlClient}, adding schema migration and {@link Repository} flyweight support (instance caching)
 * to further abstract database interactions.
 *
 * <h2>Obtaining repository instances</h2>
 * <ul>
 * <li>{@link #getRepository(Class, Class)} – explicit impl class specified; no registry lookup required. <br/>
 * <em>It is an error to request two different impl's for the same RepositoryApi from a single instance of a
 * {@link SqlDatabaseManager}. If you need a non-flyweight instance you should directly construct the instance
 * yourself.</em></li>
 * <li>{@link #getRepository(Class, RepositoryRegistry)} – uses the preferred impl class resolved from the
 *     registry's {@code db/registry/} bindings.</li>
 * </ul>
 * Each {@link Repository} instance is lazily constructed and cached upon the first {@code getRepository()} call
 * then that same instance is returned for every subsequent call.
 * @see RepositoryComposition
 * @see RepositoryRegistry
 */
public class SqlDatabaseManager extends SqlClient {
    private final static Logger logger = Logger.getLogger("SqlDatabaseManager");
    protected final @NotNull SchemaMigrator migrator;

    /// Cache of initialized repository instances.  Key: Repository API interface → value: instance.
    private final Map<Class<? extends Repository>, ValueOrException<Repository, DatabaseException>>
            repositoryCache = new ConcurrentHashMap<>();

    /**
     * Constructs a new manager.
     *
     * @param platformHandle The owner of this database manager.
     * @param config         Database connection configuration.
     */
    public SqlDatabaseManager(@NotNull PlatformHandle platformHandle, @NotNull SqlConnectionConfig config) {
        super(platformHandle, config);
        this.migrator = new SchemaMigrator(this);
    }

    @VisibleForTesting
    public SqlDatabaseManager(
            @NotNull PlatformHandle platformHandle,
            @NotNull SqlConnectionConfig config,
            @Nullable SchemaMigrator migrator,
            @NotNull Function<@NotNull HikariConfig, HikariDataSource> hikariCreator
    ) {
        super(platformHandle, config, hikariCreator);
        this.migrator = migrator != null ? migrator : new SchemaMigrator(this);
    }

    @Override
    public boolean setSqlConnectionConfig(@NotNull SqlConnectionConfig config) {
        final boolean connectionChanged = super.setSqlConnectionConfig(config);
        if (connectionChanged && migrator != null /*true during instance creation*/) {
            migrator.refreshVersionCache();

            // Re-run migrations and invalidate caches for any cached repositories
            Iterator<Map.Entry<Class<? extends Repository>, ValueOrException<Repository, DatabaseException>>>
                    iter = repositoryCache.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                var voe = e.getValue();
                try {
                    if (voe.hasValue()) {
                        migrator.runMigrationsFor(e.getKey());
                        voe.getValue().invalidateCaches();
                    } else {
                        // Release previously captured errors to lazily allow re-bootstrapping
                        iter.remove();
                    }
                } catch (Exception ex) {
                    logger.log(Level.SEVERE, "Error while applying required migrations for repository: "
                            + voe.getValue().getClass().getName(), ex);
                    iter.remove();
                }
            }
        }
        return connectionChanged;
    }

    /**
     * Returns an initialized repository instance of the requested type, using the given
     * impl class to instantiate it on first access.
     * <p>
     * Migrations are run the first time this method is called for a given api.
     * Successive calls return the cached flyweight instance.
     * </p>
     * <p>
     * The impl class must provide a {@code constructor(SqlClient)} matching the
     * {@link Repository} contract.
     * </p>
     *
     * @param api       The {@link Repository} API interface to retrieve.
     * @param implClass The concrete implementation class to instantiate if an instance is not already cached.
     * @return Flyweight instance of the requested repository. Successive calls to this method will return the same
     * instance.
     * @param <I> Repository interface type.
     * @throws RepositoryInitializationException If the impl class cannot be instantiated,
     *         or if a different impl was already cached for this api.
     */
    @SuppressWarnings("unchecked")
    public <I extends Repository> @NotNull I getRepository(
            @NotNull Class<I> api,
            @NotNull Class<? extends I> implClass
    ) throws RepositoryInitializationException {
        var voe = repositoryCache.get(api);
        if (voe != null) {
            I instance = (I) voe.getOrThrow();
            if (!implClass.isInstance(instance)) {
                throw new RepositoryInitializationException(
                        "Cache collision for api " + api.getName()
                                + ": already bound to " + instance.getClass().getName()
                                + " but requested " + implClass.getName()
                                + ". Each api type must map to exactly one impl per manager.");
            }
            return instance;
        }

        try {
            migrator.runMigrationsFor(api);
            var ctor = implClass.getConstructor(SqlClient.class);
            I instance = api.cast(ctor.newInstance(this));
            voe = ValueOrException.forValue(instance);
        } catch (RepositoryInitializationException ex) {
            voe = ValueOrException.forException(ex);
        } catch (Exception ex) {
            voe = ValueOrException.forException(
                    new RepositoryInitializationException("Failed to instantiate: " + implClass.getName(), ex));
        }

        repositoryCache.put(api, voe);
        return (I) voe.getOrThrow();
    }

    /**
     * Returns an initialized repository instance of the requested type, resolving the
     * impl class from {@code registry}'s impl bindings.
     * <p>
     * Equivalent to {@code getRepository(api, registry.findImplementation(api))}.
     * </p>
     *
     * @param api      The {@link Repository} API interface to retrieve.
     * @param registry The registry holding the api-to-impl binding.
     * @return Flyweight instance of the requested repository.
     * @param <I> Repository interface type.
     * @throws RepositoryInitializationException If the impl cannot be resolved or instantiated.
     */
    @SuppressWarnings("unchecked")
    public <I extends Repository> @NotNull I getRepository(
            @NotNull Class<I> api,
            @NotNull RepositoryRegistry registry
    ) throws RepositoryInitializationException {
        Class<? extends I> implClass = (Class<? extends I>) registry.getImplementationType(api);
        return getRepository(api, implClass);
    }
}
