package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Base interface for all repository types.
 * <ol>
 * <li>Implementors <b>MUST</b> define an interface annotated with {@link RepositoryApi}
 *     that <b>directly extends</b> {@code Repository}.
 * <li>Extending multiple {@code @RepositoryApi} interfaces is forbidden.</li>
 * <li>Implementors <b>MUST</b> provide a default {@code constructor(SqlClient)}.</li>
 * </ol>
 *
 * @see RepositoryApi
 * @see AbstractRepository
 * @see RepositoryRegistry
 * @see RepositoryComposition
 */
public interface Repository {

    /**
     * Requests that this repository invalidate any caches it may hold and notify all
     * subscribed {@link #onCacheInvalidatedEvent()} listeners to do the same.
     *
     * @implNote Implementors must fire {@code onCacheInvalidatedEvent.accept(this);}.
     */
    void invalidateCaches();

    /**
     * Provides a hook to be notified when cached data pertinent to this repository
     * has become invalid.
     */
    @NotNull SubscribableEvent<Repository> onCacheInvalidatedEvent();

    /**
     * Identifies the single {@code @RepositoryApi} interface implemented by the given class.
     * <p>
     * Validates that:
     * <ul>
     * <li>Exactly one {@code @RepositoryApi} interface exists in the hierarchy</li>
     * <li>That interface directly extends {@code Repository}</li>
     * <li>The annotation is only on interfaces that extend {@code Repository}</li>
     * </ul>
     *
     * @param clazz A concrete repository implementation class or a {@code @RepositoryApi} interface.
     * @return The single {@code @RepositoryApi} interface.
     * @throws IllegalArgumentException If validation fails.
     */
    @SuppressWarnings("unchecked")
    static Class<? extends Repository> identifyRepositoryApi(@NotNull Class<? extends Repository> clazz) {
        List<Class<? extends Repository>> found = new ArrayList<>();
        collectRepositoryApis(clazz, found);

        if (found.isEmpty()) {
            throw new IllegalArgumentException(clazz.getName()
                    + " does not implement any interfaces annotated with @RepositoryApi");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(clazz.getName()
                    + " implements multiple @RepositoryApi interfaces: "
                    + found.stream().map(Class::getSimpleName).toList()
                    + ". Each implementation must implement exactly one @RepositoryApi interface. "
                    + "You probably want to make a RepositoryComposition instead.");
        }
        return found.getFirst();
    }

    static void validateRepositoryApi(@NotNull Class<? extends Repository> api) {
        if (!api.isInterface()) {
            throw new IllegalArgumentException(api.getName() + " must be an interface.");
        }
        identifyRepositoryApi(api);
    }

    @SuppressWarnings("unchecked")
    private static void collectRepositoryApis(Class<?> clazz, List<Class<? extends Repository>> collected) {
        if (clazz == null || clazz == Object.class) return;

        RepositoryApi annot = clazz.getAnnotation(RepositoryApi.class);
        if (annot != null) {
            if (!clazz.isInterface() || !Repository.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(clazz.getName()
                        + " has @RepositoryApi but is not a Repository interface. "
                        + "Only Repository interfaces may define migration schemas.");
            }
            // Enforce single-level: the @RepositoryApi interface must directly extend Repository
            boolean directlyExtendsRepository = false;
            for (Class<?> parent : clazz.getInterfaces()) {
                if (parent == Repository.class) {
                    directlyExtendsRepository = true;
                } else if (parent.isAnnotationPresent(RepositoryApi.class)) {
                    throw new IllegalArgumentException(clazz.getName()
                            + " extends another @RepositoryApi interface (" + parent.getSimpleName()
                            + "). @RepositoryApi interfaces must directly extend Repository; "
                            + "API inheritance chains are not supported.");
                }
            }
            if (!directlyExtendsRepository) {
                throw new IllegalArgumentException(clazz.getName()
                        + " is annotated with @RepositoryApi but does not directly extend Repository.");
            }
            collected.add((Class<? extends Repository>) clazz);
        }

        for (Class<?> iface : clazz.getInterfaces()) {
            collectRepositoryApis(iface, collected);
        }
        collectRepositoryApis(clazz.getSuperclass(), collected);
    }

    /**
     * Returns the migration area names declared by the given repository API interface.
     *
     * @param repoApi A {@code @RepositoryApi}-annotated interface.
     * @return Immutable list of migration area names.
     */
    static List<String> collectMigrationNames(@NotNull Class<? extends Repository> repoApi) {
        RepositoryApi annot = repoApi.getAnnotation(RepositoryApi.class);
        if (annot == null) {
            throw new IllegalArgumentException(repoApi.getName() + " is not annotated with @RepositoryApi");
        }
        return Arrays.stream(annot.value())
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    /**
     * Returns the set of dialects declared by {@link RepositoryImpl @RepositoryImpl} on {@code implClass}.
     *
     * @throws IllegalStateException If the annotation is missing, the dialects array is empty,
     *                               or the array contains {@link SqlDialect#UNDEFINED}.
     */
    static Set<SqlDialect> supportedDialectsOf(Class<?> implClass) {
        RepositoryImpl annot = implClass.getAnnotation(RepositoryImpl.class);
        if (annot == null || annot.dialects().length == 0) {
            throw new IllegalStateException(
                    implClass.getName() + " must be annotated with @RepositoryImpl declaring at least one dialect.");
        }
        EnumSet<SqlDialect> set = EnumSet.copyOf(Arrays.asList(annot.dialects()));
        if (set.contains(SqlDialect.UNDEFINED)) {
            throw new IllegalStateException(
                    implClass.getName() + " declares @RepositoryImpl with SqlDialect.UNDEFINED, which is not allowed.");
        }
        return set;
    }

    /**
     * Returns {@code true} if {@code implClass} declares support for {@code active} via
     * {@link RepositoryImpl @RepositoryImpl}.
     *
     * @throws IllegalArgumentException If {@code active} is {@link SqlDialect#UNDEFINED}.
     * @throws IllegalStateException    If {@code implClass} has a missing or invalid {@code @RepositoryImpl}.
     */
    static boolean supportsDialect(Class<?> implClass, @NotNull SqlDialect active) {
        if (active == SqlDialect.UNDEFINED) {
            throw new IllegalArgumentException("active dialect must not be UNDEFINED");
        }
        return supportedDialectsOf(implClass).contains(active);
    }
}
