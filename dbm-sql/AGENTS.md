# AGENTS.md — dbm-sql

Core SQL layer. No dependency on `dbm-core` or platform modules. Java 21.

## Packages

| Package | Contents |
|---|---|
| `io.github.ensgijs.dbm.sql` | `SqlClient`, `ExecutionContext`, `SqlConnectionConfig` (+ `MySqlConnectionConfig`, `SqliteConnectionConfig`, `SqliteMemoryConnectionConfig`), `SqlDialect`, `UpsertStatement`, `StatementExecutor`, `SqlStatementSplitter`, `DatabaseException`, `ChunkedBatchExecutionException` |
| `io.github.ensgijs.dbm.platform` | `PlatformHandle`, `SimplePlatformHandle` — abstraction over the host environment (plugin name, classloader, lifecycle hooks) |
| `io.github.ensgijs.dbm.util` | `BubbleUpException` and subpackages: `function/` (throwing functional interfaces, `ValueOrException`), `io/` (`ResourceScanner`, `ResourceEntry`), `objects/` (`ObjectHelpers`), `threading/` (`ExecutorLimiter`, `LimitedVirtualThreadPerTaskExecutor`, async test helpers) |

## Core abstractions (god nodes)

- `SqlClient` — entry point. Owns the HikariCP pool. Methods: `executeUpdate`, `executeQuery`, `executeUpsert`, `executeSession` (auto-commit per stmt), `executeTransaction` (atomic), `executeBatch`, `executeChunkedBatch`.
- `ExecutionContext` — passed into session/transaction lambdas; same execute methods minus pool management.
- `SqlConnectionConfig` (sealed-style hierarchy: `MySqlConnectionConfig`, `SqliteConnectionConfig`, `SqliteMemoryConnectionConfig`) — exposes `getDbUrl()`, `configurePool(HikariConfig)`, `maxConnections()`, `isEquivalent(other)`, and `dialect()`.
- `UpsertStatement` — builder that emits dialect-correct `INSERT ... ON CONFLICT` / `ON DUPLICATE KEY UPDATE` SQL. Use `db.executeUpsert(stmt, args...)` or `db.sql(stmt)` for batch.
- `SqlDialect` — enum (`MYSQL`, `SQLITE`); single source of truth for dialect branching.

## Reusable utilities (`io.github.ensgijs.dbm.util`)

These are first-class library APIs, not just internal helpers — `dbm-core` and downstream consumers are expected to use them instead of rolling their own. Prefer these over reaching for `java.util.concurrent` or third-party equivalents.

| Utility | Package | Purpose |
|---|---|---|
| `SubscribableEvent<T>` (interface) | `util.objects` | Minimal subscribe/unsubscribe API exposed to callers — use as the public-facing return type so subscribers cannot fire the event. |
| `ConsumableSubscribableEvent<T>` | `util.objects` | Thread-safe multi-subscriber implementation. Implements `Consumer<T>` so it can be passed as a callback (tee pattern). Two usage modes: **direct** (own a field, expose it as `SubscribableEvent`) and **elevation** (own a `Consumer` field, use the static `subscribe`/`unsubscribe`/`update` helpers — caller must synchronize). |
| `OneShotConsumableSubscribableEvent<T>` | `util.objects` | One-shot variant. After `accept(value)` fires, late subscribers are invoked inline with the captured value. Use for "loaded" / "ready" / "first-time-config" signals. `accept()` may only be called once (throws `IllegalStateException` on a different value). |
| `ValueOrException<V, E>` | `util.objects` | Carries either a value or a checked exception through a pipeline. `eval(supplier)` to capture, `wrap(fn)` to lift a throwing function into a `Function<T, ValueOrException<...>>` for streams, `getOrThrow()` / `getOrThrow(WrapperType.class)` to surface. Use this instead of `try/catch` in stream/lambda chains. |
| `ObjectHelpers` | `util.objects` | Null-safe `as(obj, Class)` cast, `asEnum(string, default)` / `asEnum(string, Class)`, `coalesce(a, b)` and varargs `coalesce(...)`. Use these instead of inline `instanceof`+cast or null-checks for cleaner intent. |
| `ExecutorLimiter` | `util.threading` | Wraps any `Executor` and caps how many tasks can run on it concurrently. Lets callers share one global pool while constraining specific subsystems. Prefer this over creating a second `ThreadPoolExecutor`. |
| `LimitedVirtualThreadPerTaskExecutor` | `util.threading` | `ExecutorLimiter` over a fresh `Executors.newVirtualThreadPerTaskExecutor()`. Default choice for bounded virtual-thread concurrency in this project. |

## Dependencies

```
HikariCP, mysql-connector-j, sqlite-jdbc   (implementation, db-connectivity bundle)
org.jetbrains:annotations                  (compileOnly)
```

No transitive deps from other dbm-eternal modules. **Do not introduce a dependency on `dbm-core`** — the layering is one-way (core → sql).

## Tests

- `./gradlew :dbm-sql:test`
- MySQL integration tests (`MySqlTests`, `SqlConnectionConfigTest` MySQL paths) require a reachable MySQL instance; SQLite tests run in-memory.
- Threading utilities have async test helpers in `util/threading/` (`AsyncVerifier`, `BlockingTestRunnable`, `OneShotCondition`) — reuse these instead of `Thread.sleep` in new tests.

## Conventions

- Throw `DatabaseException` (or its subtypes like `ChunkedBatchExecutionException`) for SQL failures; do not surface raw `SQLException` across the public API.
- Keep dialect branching inside `SqlDialect` consumers (`UpsertStatement`, `*ConnectionConfig`); do not scatter `if (dialect == ...)` across `SqlClient`.
- New connection-config types must implement `isEquivalent` so pool reuse logic works.
