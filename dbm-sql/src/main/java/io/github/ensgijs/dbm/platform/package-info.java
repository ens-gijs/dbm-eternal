/**
 * Platform abstraction layer.
 *
 * <p>
 * {@link io.github.ensgijs.dbm.platform.PlatformHandle} decouples the SQL layer from any
 * specific host environment. It supplies a name (for logging and connection pool identification)
 * and a data folder (for SQLite file placement). Dependency relationships between handles drive
 * callback ordering in the
 * {@link io.github.ensgijs.dbm.repository.RepositoryRegistry}.
 * </p>
 *
 * <p>
 * {@link io.github.ensgijs.dbm.platform.SimplePlatformHandle} is a general-purpose record
 * implementation suitable for tests and standalone applications. Platform-specific modules
 * (e.g., {@code dbm-platform-paper}) provide implementations backed by their respective
 * plugin APIs.
 * </p>
 */
package io.github.ensgijs.dbm.platform;
