/**
 * Platform abstraction used by the {@code RepositoryRegistry} to identify and order plugins
 * during bootstrap.
 *
 * <p>
 * {@link io.github.ensgijs.dbm.platform.PlatformHandle} supplies a unique name plus optional
 * dependency relationships between handles that drive {@code onConfigure} / {@code onReady}
 * callback ordering in the {@code RepositoryRegistry} and disambiguate conflicting bindings.
 * </p>
 *
 * <p>
 * {@link io.github.ensgijs.dbm.platform.SimplePlatformHandle} is a general-purpose record
 * implementation suitable for tests and standalone applications.
 * </p>
 *
 * <p>
 * The SQL layer ({@code SqlClient}) and the repository facade ({@code SqlDatabaseManager}) do not depend on this type;
 * it accepts a plain {@code String label} for pool-name and log identification. Code wiring up
 * a manager from inside a registry callback may forward {@code platformHandle.name()} as that
 * label.
 * </p>
 */
package io.github.ensgijs.dbm.platform;
