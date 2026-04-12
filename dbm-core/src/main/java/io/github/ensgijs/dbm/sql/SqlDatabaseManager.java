package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.util.objects.ValueOrException;
import io.github.ensgijs.dbm.migration.SchemaMigrator;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryInitializationException;
import io.github.ensgijs.dbm.repository.RepositoryRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds upon {@link SqlClient}, adding schema migration and {@link Repository} flyweight support
 * to further abstract database interactions.
 */
public class SqlDatabaseManager extends SqlClient {
    private final static Logger logger = Logger.getLogger("SqlDatabaseManager");
    protected final @NotNull SchemaMigrator migrator;
    protected final @NotNull RepositoryRegistry registry;

    /// Cache of initialized repository instances.
    /// Map of Interface -> Object instance.
    private final Map<Class<? extends Repository>, ValueOrException<Repository, DatabaseException>>
            repositoryInstances = new ConcurrentHashMap<>();

    /**
     * Initializes the manager and attempts to load the database configuration
     * from the plugin's default config file.
     * @param platformHandle The owner of this database manager.
     * @param dbmConfig {@link DbmConfig}
     */
    public SqlDatabaseManager(@NotNull PlatformHandle platformHandle, @NotNull DbmConfig dbmConfig) {
        super(platformHandle, dbmConfig.sqlConnectionConfig());
        this.registry = RepositoryRegistry.globalRegistry();
        this.migrator = new SchemaMigrator(this);
        registerProvider(dbmConfig.provides());
    }

    @VisibleForTesting
    public SqlDatabaseManager(@NotNull PlatformHandle platformHandle, @NotNull DbmConfig dbmConfig, @Nullable SchemaMigrator migrator, @Nullable RepositoryRegistry registry, @NotNull Function<@NotNull HikariConfig, HikariDataSource> hikariCreator) {
        super(platformHandle, dbmConfig.sqlConnectionConfig(), hikariCreator);
        this.registry = registry != null ? registry : RepositoryRegistry.globalRegistry();
        this.migrator = migrator != null ? migrator : new SchemaMigrator(this);
    }

    /**
     * Attempts to register this manager as the default provider for a list of repository
     * API <b>interfaces</b>.
     * <p>
     * This method reads the {@code provides} string list from the resolved database
     * configuration section. For each FQCN (Fully Qualified Class Name) in that list,
     * it attempts to:
     * </p>
     * <ol>
     * <li>Load the class using the plugin's ClassLoader.</li>
     * <li>Ensure the class is a subclass of {@link Repository}.</li>
     * <li>Nominate this {@link SqlDatabaseManager} instance as the official
     * provider in the {@link RepositoryRegistry}.</li>
     * </ol>
     * <p>
     * If a class in the {@code provides} list cannot be found, a warning is logged but
     * bootstrapping continues for other entries.
     * </p>
     * <p>
     * <b>Note:</b> This method is called by this classes constructor, if a descendant overrides this
     * method it should check {@code registry.isAcceptingNominations()} and act intelligently.
     * </p>
     */
    protected void registerProvider(List<Class<? extends Repository>> provides) {
        for (var registryType : provides) {
            registry.nominateDefaultProvider(registryType, this);
        }
    }

    @Override
    public boolean setSqlConnectionConfig(@NotNull SqlConnectionConfig config) {
        final boolean connectionChanged = super.setSqlConnectionConfig(config);
        if (connectionChanged && migrator != null /*ture during instance creation*/) {
            migrator.refreshVersionCache();

            // perform migrations against new connection
            var iter = repositoryInstances.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                var voe = e.getValue();
                try {
                    if (voe.hasValue()) {
                        migrator.runMigrationsFor(e.getKey());
                        voe.getValue().invalidateCaches();
                    } else {
                        // release previously captured errors to lazily allow a re-bootstrapping attempt by getRepository()
                        iter.remove();
                    }
                } catch (Exception ex) {
                    logger.log(Level.SEVERE, "Error while applying required migrations for repository: " + voe.getValue().getClass().getName(), ex);
                    // release to lazily allow a re-bootstrapping attempt by getRepository()
                    iter.remove();
                }
            }
        }
        return connectionChanged;
    }

    /**
     * Returns an initialized repository instance of the requested type.
     * Migrations will be run the first time this method is called for a repository.
     * Successive calls will return the same repository instance.
     * @param repoInterface {@link Repository} requested.
     * @return Flyweight instance of the requested repository.
     * @param <I> Repository interface type.
     * @throws RepositoryInitializationException
     */
    @SuppressWarnings("unchecked")
    public <I extends Repository> @NotNull I getRepository(@NotNull Class<I> repoInterface) throws RepositoryInitializationException {
        var voe = repositoryInstances.get(repoInterface);
        if (voe != null) return (I) voe.getOrThrow();

        final RepositoryRegistry.RepositoryImplementorCandidate repositoryCandidate = registry.getElectedRepositoryImplementorCandidate(repoInterface);
        try {
            // Only migrate the top api, runMigrations will navigate the hierarchy respecting inheritMigrations from here
            migrator.runMigrationsFor(repositoryCandidate.apiTypes().getFirst());
            try {
                voe = ValueOrException.forValue(repositoryCandidate.createInstance(this));
            } catch (DatabaseException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new RepositoryInitializationException("Failed to instantiate: " + repositoryCandidate.implementationType().getName(), ex);
            }
        } catch (Exception ex) {
            voe = ValueOrException.forException(new RepositoryInitializationException("Error while setting up repository for: " + repoInterface.getName(), ex));
        }

        for (var api : repositoryCandidate.apiTypes()) {
            repositoryInstances.put(api, voe);
        }
        return (I) voe.getOrThrow();
    }
}
