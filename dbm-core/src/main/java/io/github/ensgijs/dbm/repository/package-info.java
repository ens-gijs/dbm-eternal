/**
 * Repository pattern with a plugin-friendly voting and election registry.
 *
 * <h2>Core Concepts</h2>
 * <dl>
 * <dt>{@link io.github.ensgijs.dbm.repository.Repository}</dt>
 * <dd>Marker interface for all repository types. Implementations obtain their database
 *     connection from a {@link io.github.ensgijs.dbm.sql.SqlDatabaseManager} and expose
 *     a cache-invalidation event.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryApi}</dt>
 * <dd>Annotation required on every public-facing repository interface. Declares which
 *     migration areas the interface depends on.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.AbstractRepository}</dt>
 * <dd>Convenience base class providing a logger, the database manager reference, and a
 *     thread-safe cache-invalidation event implementation.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryRegistry}</dt>
 * <dd>Central singleton (or scoped instance) that manages the bootstrap lifecycle.
 *     During the <em>voting phase</em>, plugins nominate implementations and providers.
 *     After {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#closeRegistration()}
 *     is called, the winners are elected and repositories become available via
 *     {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#getDefaultRepository(Class)}.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryComposition}</dt>
 * <dd>Aggregates multiple {@link io.github.ensgijs.dbm.repository.Repository} instances into
 *     a single service object without owning tables directly.</dd>
 * </dl>
 *
 * <h2>Bootstrap Lifecycle</h2>
 * <ol>
 * <li>Each plugin calls {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#register}
 *     and configures an {@code onConfigure} callback to nominate providers and implementations.</li>
 * <li>After all plugins have registered, {@code closeRegistration()} is called. Elections are
 *     resolved and {@code onPrepare} / {@code onReady} callbacks fire on a virtual thread.</li>
 * <li>Repositories are accessed via {@code getDefaultRepository(Class)} or directly from a
 *     {@link io.github.ensgijs.dbm.sql.SqlDatabaseManager}.</li>
 * </ol>
 *
 * <h2>Implementation Discovery</h2>
 * <p>
 * Concrete implementations are auto-discovered from classpath resources under
 * {@code db/registry/}. Each file's name must be the fully-qualified class name of a
 * concrete {@link io.github.ensgijs.dbm.repository.Repository} implementation.
 * Implementations may also be nominated programmatically via the {@code onConfigure} callback.
 * </p>
 */
package io.github.ensgijs.dbm.repository;
