package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlClient;
import io.github.ensgijs.dbm.util.objects.ConsumableSubscribableEvent;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Provides the boilerplate required for any {@link Repository} implementation.<br/>
 * Implementors <b>MUST</b>:
 * <ol>
 * <li>Define and implement an <b>interface</b> which describes the repository API.
 * <li>The repository API interface <b>MUST</b> extend {@link Repository}.
 * <li>The repository API interface <b>MUST</b> be annotated with {@link RepositoryApi}.
 * <li>Provide a constructor accepting a single {@link SqlClient} argument.
 * </ol>
 * @see Repository
 */
public abstract class AbstractRepository implements Repository {
    protected final @NotNull SqlClient sqlClient;
    protected final @NotNull ConsumableSubscribableEvent<Repository> onCacheInvalidatedEvent = new ConsumableSubscribableEvent<>();

    public AbstractRepository(@NotNull SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    /**
     * Call to request that this {@link Repository} should invalidate any caches it may hold and to
     * notify all subscribed {@link #onCacheInvalidatedEvent()} listeners to do the same.
     *
     * @implNote if you override this method remember to call {@code super.invalidateCaches()} or
     * run {@code onCacheInvalidatedEvent.accept(this);} in your overload.
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
