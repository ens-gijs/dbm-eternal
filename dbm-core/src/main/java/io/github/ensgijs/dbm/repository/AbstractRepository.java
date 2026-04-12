package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.util.objects.ConsumableSubscribableEvent;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import io.github.ensgijs.dbm.sql.SqlConnectionConfig;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * Provides the boilerplate required for any {@link Repository} implementation.<br/>
 * Implementors <b>MUST</b>:
 * <ol>
 * <li>Define and implement an <b>interface</b> which describes the repository API.
 * <li>The repository API interface <b>MUST</b> extend {@link Repository}.
 * <li>The repository API interface <b>MUST</b> be annotated with {@link RepositoryApi}.
 * </ol>
 * @see Repository
 */
public abstract class AbstractRepository implements Repository {
    protected final @NotNull Logger logger;
    protected final @NotNull SqlDatabaseManager databaseManager;
    protected final @NotNull ConsumableSubscribableEvent<Repository> onCacheInvalidatedEvent = new ConsumableSubscribableEvent<>();

    public AbstractRepository(@NotNull SqlDatabaseManager databaseManager) {
        this.logger = Logger.getLogger(this.getClass().getSimpleName());
        this.databaseManager = databaseManager;
    }

    @Override
    public final @NotNull SqlDatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * Call to request that this {@link Repository} should invalidate any caches it may hold and to
     * notify all subscribed {@link #onCacheInvalidatedEvent()} listeners to do the same.
     *
     * @implNote if you override this method remember to call {@code super.invalidateCaches()} or
     * run {@code onCacheInvalidatedEvent.accept(this);} in your overload.
     * <p>When called by an owning {@link SqlDatabaseManager} in response to a {@link SqlConnectionConfig}
     * change, the existing connection pool has already been drained and restarted and any required db migrations have
     * been applied prior to this method being called.</p>
     */
    @Override
    public void invalidateCaches() {
        onCacheInvalidatedEvent.accept(this);
    }

    /**
     * Provides a hook to be notified when any and all cached data pertinent to this repository
     * has become invalid.
     */
    @Override
    public final @NotNull SubscribableEvent<Repository> onCacheInvalidatedEvent() {
        return onCacheInvalidatedEvent;
    }
}