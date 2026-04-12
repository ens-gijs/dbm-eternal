package io.github.ensgijs.dbm.repository;

import org.jetbrains.annotations.NotNull;

/**
 * Functional aggregators that wrap one or more {@link Repository} instances.
 * <p>
 * {@link RepositoryRegistry} offers facilities to flyweight composition instances making shared instances globally
 * available (effectively singleton). Implementors must define a constructor taking only a {@link RepositoryRegistry},
 * a default no-args constructor or pre-register a creator with
 * {@link RepositoryRegistry#nominateRepositoryComposition} to be able to make use
 * of {@link RepositoryRegistry#getCompositeRepository(Class)}.
 * </p>
 * <p>
 * {@link RepositoryComposition}'s may not possess the {@link RepositoryApi} annotation, implement {@link Repository}
 * or own tables directly.
 * </p>
 * @apiNote <b>Best Practices:</b>
 * <ul>
 * <li>{@link RepositoryComposition}'s must NOT call {@link RepositoryRegistry#getCompositeRepository(Class)} from
 * their constructor, they should wait until {@link #onInitialize(RepositoryRegistry)} is called.
 * <li>{@link RepositoryComposition}'s should use the passed {@link RepositoryRegistry} instead of accessing the
 * singleton {@link RepositoryRegistry#globalRegistry()}.
 * </ul>
 */
public interface RepositoryComposition {
    /**
     * Phase 2 of the create-instantiate pattern. It is safe to call {@link RepositoryRegistry#getCompositeRepository(Class)}
     * from within this handler but not guaranteed safe to use the returned result due to the risk of bidirectional
     * dependency.
     */
    default void onInitialize(@NotNull RepositoryRegistry registry) throws RepositoryInitializationException {}
}
