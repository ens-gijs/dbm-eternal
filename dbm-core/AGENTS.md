# AGENTS.md — dbm-core

Schema migration engine + repository registry. Built on `dbm-sql`. Java 21.

## Packages

| Package | Contents |
|---|---|
| `io.github.ensgijs.dbm.sql` | `SqlDatabaseManager` — wraps `SqlClient` and binds it to a migration set + repository scope |
| `io.github.ensgijs.dbm.migration` | `Migration` (+ `Migration.Key`, `Migration.MigrationSource`, `Migration.JavaSource`, `Migration.ProgrammaticMigration`), `MigrationLoader`, `SchemaMigrator`, `MigrationParseException` |
| `io.github.ensgijs.dbm.repository` | `Repository`, `RepositoryApi` (annotation), `RepositoryImpl` (annotation), `AbstractRepository`, `RepositoryRegistry` (+ `RegistrationOptions`, `ConflictMode`, `RegistrationHelper`, `RegistrationBootstrappingContext`), `RepositoryComposition`, `AmbiguousRepositoryApiException`, `RepositoryInitializationException`, `RepositoryNotRegisteredException` |

## Core abstractions

- `SqlDatabaseManager` — public entrypoint pairing a `SqlClient` with a migration set. Repository implementations receive one as a `SqlClient` via their constructor.
- `RepositoryRegistry` — provides local or global multi-plugin registry with publish/bind/close lifecycle. Conflict resolution via `ConflictMode` + `RegistrationOptions`. `@RepositoryApi("name")` marks an interface; `@RepositoryImpl(dialect = ...)` filters implementations by dialect.
- `SchemaMigrator` — discovers, sorts (topological by `!AFTER` directives + version), and applies migrations.
- `MigrationLoader` — parses migration filenames (`{name}.{version}[.{dialect}].{ext}`) and source bodies. `.sql` files require a dialect; `.run` files reference a `Migration.ProgrammaticMigration` Java class. See [README.md](../README.md#4-define-migrations) for the full naming spec.

## Conventions

- Repository implementations: extend `AbstractRepository`, take `SqlClient` in the constructor, register default mappings by placing a service locator file at `src/main/resources/db/registry/<fully.qualified.RepoApiInterfaceName>` (with one line: `<fully.qualified.ImplClassName>`).
- Migrations live at `src/main/resources/db/migrate/`. Use `!AFTER: <name>.<version>` at the top of a file to declare dependencies.
## Events — use `SubscribableEvent`, do not poll

Lifecycle and validation signals in `dbm-core` (e.g. dialect-change validation, registry ready/closed, migration progress) are exposed via the `SubscribableEvent` family from `dbm-sql` (`io.github.ensgijs.dbm.util.objects`). When adding new notifications to `dbm-core` or where subscribe-notify/observer solutions are needed:

- **Public API returns `SubscribableEvent<T>`.** Own a `ConsumableSubscribableEvent<T>` (or `OneShotConsumableSubscribableEvent<T>`) as a private field; expose it via a getter typed as `SubscribableEvent<T>` so callers can subscribe but not fire.
- **One-shot signals** (loaded, first-bind, registration-closed) → `OneShotConsumableSubscribableEvent`. Late subscribers will be invoked inline with the captured value, which is usually what you want for "did this happen yet?" checks.
- **Repeatable signals** (per-migration, per-validation) → `ConsumableSubscribableEvent`.
- Do not introduce ad-hoc listener interfaces, custom `List<Consumer<...>>` plumbing, or polling loops for state that has a natural event boundary.

## Dependencies

```
project(':dbm-sql')                        (implementation)
HikariCP                                   (implementation, direct — also transitive via dbm-sql)
org.jetbrains:annotations                  (compileOnly)
```

Layering rule: `dbm-core` depends on `dbm-sql`, never the reverse. No dependency on platform modules.

## Tests

- `./gradlew :dbm-core:test`
- Reusable fixtures live alongside tests in `repository/` (e.g., `BaseRepo`, `FakeRepository`, `AbstractBaseLogic`, `DeepChainImpl`) — extend these for new registry-conflict / composition cases instead of inventing fresh dummies.
- `RepositoryContractTest` defines behavioral guarantees that all repository impls must honor; new repository abstractions should plug into it.
