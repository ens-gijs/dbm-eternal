package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlConnectionConfig;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * <ol>
 * <li>Implementors <b>MUST</b> define an interface which describes the repository API.
 * <li>The repository API interface (which must extend this one) <b>MUST</b> be annotated with {@link RepositoryApi}.
 * <li>Intermediate interfaces without the {@link RepositoryApi} annotation are allowed.
 * <li>Extensions of api's is allowed, when registering implementations with {@link RepositoryRegistry}
 * more specific api types (most distant ancestor) will be preferred and trump registrations for lower api's
 * at any priority.
 * <li>Implementors <b>MUST</b> provide a default {@code constructor(SqlDatabaseManager)}.
 * </ol>
 * @see RepositoryApi
 * @see AbstractRepository
 * @see RepositoryRegistry
 * @see RepositoryComposition
 */
public interface Repository {

    @NotNull SqlDatabaseManager getDatabaseManager();

    /**
     * Call to request that this {@link Repository} should invalidate any caches it may hold and to
     * notify all subscribed {@link #onCacheInvalidatedEvent()} listeners to do the same.
     * @implNote Implementors must run {@code onCacheInvalidatedEvent.accept(this);}.
     * <p>When called by an owning {@link SqlDatabaseManager} in response to a {@link SqlConnectionConfig}
     * change, the existing connection pool has already been drained and restarted and any required db migrations have
     * been applied prior to this method being called.</p>
     */
    void invalidateCaches();

    /**
     * Provides a hook to be notified when any and all cached data pertinent to this repository
     * has become invalid.
     */
    @NotNull SubscribableEvent<Repository> onCacheInvalidatedEvent();

    default List<Class<? extends Repository>> collectAllImplementedRepoApis() {
        return collectAllImplementedRepoApis(this.getClass());
    }

    /// Scans hierarchy for  @RepositoryApi's, verifies linear inheritance, returns list of @RepositoryApi interfaces
    /// in the order they were encountered (the one closest to clazz is first).
    static List<Class<? extends Repository>> collectAllImplementedRepoApis(Class<? extends Repository> clazz) {
        // Discover all APIs this class fulfills
        Set<Class<? extends Repository>> collectedApis = new LinkedHashSet<>();
        collectAllImplementedRepoApis(clazz, collectedApis);

        if (collectedApis.isEmpty()) {
            throw new IllegalArgumentException(clazz.getName()
                    + " does not implement any interfaces annotated with @RepositoryApi");
        }

        // Diamond Detection: Ensure the inheritance of @RepositoryApi interfaces is strictly linear
        List<Class<? extends Repository>> apiList = new ArrayList<>(collectedApis);
        for (int i = 0; i < apiList.size(); i++) {
            for (int j = i + 1; j < apiList.size(); j++) {
                Class<? extends Repository> a = apiList.get(i);
                Class<? extends Repository> b = apiList.get(j);

                // If neither is assignable from the other, they are on separate branches (Fork/Diamond)
                if (!a.isAssignableFrom(b) && !b.isAssignableFrom(a)) {
                    throw new IllegalArgumentException(String.format(
                            "Diamond inheritance detected in %s! It implements unrelated @RepositoryApi interfaces: %s and %s",
                            clazz.getSimpleName(), a.getSimpleName(), b.getSimpleName()
                    ));
                }
            }
        }
        return apiList;
    }

    @SuppressWarnings("unchecked")
    private static void collectAllImplementedRepoApis(final Class<?> clazz, final Set<Class<? extends Repository>> collected) {
        if (clazz == null || clazz == Object.class) return;

        RepositoryApi annot = clazz.getAnnotation(RepositoryApi.class);
        if (annot != null) {
            if (clazz.isInterface() && Repository.class.isAssignableFrom(clazz)) {
                collected.add((Class<? extends Repository>) clazz);
            } else {
                // Forbid the annotation on concretions or abstract classes to keep schema ownership clear
                throw new IllegalArgumentException(clazz.getName() + " has @RepositoryApi but is not a Repository interface. " +
                        "Only Repository interfaces may define migration schemas.");
            }
        }

        // Scan interfaces
        for (Class<?> iface : clazz.getInterfaces()) {
            collectAllImplementedRepoApis(iface, collected);
        }

        // Scan superclass (in case the user is extending a base implementation class)
        collectAllImplementedRepoApis(clazz.getSuperclass(), collected);
    }

    default List<String> collectMigrationNames() {
        return collectMigrationNames(this.getClass());
    }

    /**
     * Helper to collect migration names from the hierarchy.
     */
    static List<String> collectMigrationNames(Class<? extends Repository> clazz) {
        Set<String> collected = new LinkedHashSet<>();
        List<Class<? extends Repository>> apis = collectAllImplementedRepoApis(clazz);
        for (var api : apis) {
            RepositoryApi annot = api.getAnnotation(RepositoryApi.class);
            Arrays.stream(annot.value())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(() -> collected, Set::add, Set::addAll);
            if (!annot.inheritMigrations())
                break;
        }
        return List.copyOf(collected);
    }
}
