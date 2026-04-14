/**
 * Repository pattern with a service-locator registry and structured bootstrap lifecycle.
 *
 * <h2>Core Concepts</h2>
 * <dl>
 * <dt>{@link io.github.ensgijs.dbm.repository.Repository}</dt>
 * <dd>Base interface for all repository types.  Each concrete implementation must provide
 *     a {@code constructor(SqlClient)} and implement exactly one
 *     {@link io.github.ensgijs.dbm.repository.RepositoryApi}-annotated interface that
 *     directly extends {@code Repository}.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryApi}</dt>
 * <dd>Annotation required on every public-facing repository interface.  Declares which
 *     migration areas the interface depends on.  Must be placed on an interface that
 *     directly extends {@code Repository}; API inheritance chains are not supported.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.AbstractRepository}</dt>
 * <dd>Convenience base class providing a {@code SqlClient} reference and a
 *     thread-safe cache-invalidation event implementation.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryRegistry}</dt>
 * <dd>Central service-locator (singleton or scoped) managing the bootstrap lifecycle.
 *     During the <em>configure phase</em>, plugins publish provider
 *     {@link io.github.ensgijs.dbm.sql.SqlDatabaseManager} instances.
 *     After {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#closeRegistration()}
 *     completes, repositories are accessible via
 *     {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#get(Class)}.</dd>
 *
 * <dt>{@link io.github.ensgijs.dbm.repository.RepositoryComposition}</dt>
 * <dd>Aggregates multiple {@link io.github.ensgijs.dbm.repository.Repository} instances into
 *     a single service object without owning tables directly. Contains the business logic
 *     required to coordinate actions across multiple {@link io.github.ensgijs.dbm.repository.Repository}'s.</dd>
 * </dl>
 *
 * <h2>Bootstrap Lifecycle</h2>
 * <ol>
 * <li>Each plugin calls {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#register}
 *     and configures an {@code onConfigure} callback to publish providers and register
 *     compositions.</li>
 * <li>After all plugins have registered, {@code closeRegistration()} is called.  Provider
 *     contests are resolved and {@code onReady} callbacks fire on a virtual thread or the
 *     specified {@link java.util.concurrent.Executor}.</li>
 * <li>Repositories are accessed via {@link io.github.ensgijs.dbm.repository.RepositoryRegistry#get(Class)}
 *     or directly from a {@link io.github.ensgijs.dbm.sql.SqlDatabaseManager}.</li>
 * </ol>
 *
 * <h2>Implementation Discovery</h2>
 * <p>
 * Concrete implementations are auto-discovered from classpath resources under
 * {@code db/registry/}.  Each file's <b>name</b> must be the fully-qualified class name of
 * the {@link io.github.ensgijs.dbm.repository.RepositoryApi}-annotated interface, and the
 * file's <b>content</b> must be the fully-qualified class name of the concrete implementation.
 * Bindings may also be declared programmatically via
 * {@link io.github.ensgijs.dbm.repository.RepositoryRegistry.RegistrationBootstrappingContext#bindImpl}
 * within an {@code onConfigure} callback.
 * </p>
 *
 * <h2>Implementation Advice</h2>
 * <p>
 * Use {@link io.github.ensgijs.dbm.repository.RepositoryComposition}'s to contain business logic for coordinating
 * between multiple {@link io.github.ensgijs.dbm.repository.Repository}'s. Avoid direct interactions between
 * {@link io.github.ensgijs.dbm.repository.Repository} instances.
 * </p>
 */
package io.github.ensgijs.dbm.repository;
