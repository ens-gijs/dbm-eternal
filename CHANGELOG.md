# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> While the project is at `0.x` the public API may change in any release. Backward
> compatibility is enforced starting at `1.0.0`.

## [Unreleased]

## [0.1.0] - TBD

Initial public release.

### dbm-sql
- `SqlClient` — connection pooling (HikariCP), session/transaction/batch execution.
- `SqlConnectionConfig` with built-in MySQL/MariaDB and SQLite implementations.
- `executeChunkedBatch` for large batch updates with per-chunk error reporting.
- `UpsertStatement` builder for dialect-aware upserts.
- Async/event utilities: `SubscribableEvent`, `ConsumableSubscribableEvent`, `OneShotConsumableSubscribableEvent`.
- Concurrency helpers: `ExecutorLimiter`, `LimitedVirtualThreadPerTaskExecutor`.
- Throwing functional interface family (`ThrowingFunction`, `ThrowingConsumer`, etc.).
- JPMS module name: `io.github.ensgijs.dbm.sql`.

### dbm-core
- `SqlDatabaseManager` for managing labelled SQL databases (package: `io.github.ensgijs.dbm.repository`).
- Schema migration engine: `MigrationLoader`, `SchemaMigrator`, file + programmatic migration sources.
- `RepositoryRegistry` with conflict resolution, dialect filtering, and bootstrap lifecycle.
- JPMS module name: `io.github.ensgijs.dbm.core`.

[Unreleased]: https://github.com/ens-gijs/dbm-eternal/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ens-gijs/dbm-eternal/releases/tag/v0.1.0
