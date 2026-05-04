# AGENTS.md — dbm-eternal

Platform-agnostic SQL database management library. Multi-module Gradle project, Java 21, Apache 2.0.

## Modules

| Module | Purpose | Scoped doc                              |
|---|---|-----------------------------------------|
| `dbm-sql` | Core SQL layer: `SqlClient`, connection configs (MySQL/SQLite), batch/upsert helpers, dialects, utilities | [dbm-sql/AGENTS.md](dbm-sql/AGENTS.md) |
| `dbm-core` | Schema migrations + repository registry, built on `dbm-sql` | [dbm-core/AGENTS.md](dbm-core/AGENTS.md) |

`dbm-core` depends on `dbm-sql`.

## Build & Test

| Task | Command |
|---|---|
| Build all | `./gradlew build` |
| Test all | `./gradlew test` |
| Test one module | `./gradlew :dbm-sql:test` / `./gradlew :dbm-core:test` |
| Single test class | `./gradlew :dbm-sql:test --tests "io.github.ensgijs.dbm.sql.SqlClientTest"` |
| Javadoc | `./gradlew javadoc` |

- Java toolchain: 21 (set in root `build.gradle`).
- Custom javadoc tags: `@apiNote`, `@implSpec`, `@implNote`.
- Dependency versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml). Do not hardcode versions in module `build.gradle` files.
- Maven coordinates: groupId `io.github.ens-gijs.dbm`, version `0.1.0-SNAPSHOT` (managed in root `build.gradle`). The hyphenated groupId matches the verified Central Portal namespace; Java packages are `io.github.ensgijs.dbm` (no hyphen — illegal in Java identifiers). The two are intentionally decoupled.
- JPMS module names (stable; renaming = breaking change for JPMS consumers): `io.github.ensgijs.dbm.sql` and `io.github.ensgijs.dbm.core`.
- Pre-1.0: `japicmp` is configured advisory-only (run with `./gradlew japicmp -PpreviousVersion=<v>`). At 1.0.0 flip the `failOnXxx` flags to `true` in root `build.gradle` so accidental binary breaks fail CI.

## Repository conventions

- Package root: `io.github.ensgijs.dbm` across all modules.
- Test framework: JUnit 5 (Jupiter) + Mockito; do not introduce JUnit 4 or other frameworks.
- Public API uses `org.jetbrains.annotations` (`@Nullable`, `@NotNull`) at `compileOnly` scope.
- Library code — no main classes, no `System.out` logging in production paths. Prefer throwing typed exceptions over silent failures.

## Knowledge graph

A graphify knowledge graph exists at [graphify-out/](graphify-out/). Read [graphify-out/GRAPH_REPORT.md](graphify-out/GRAPH_REPORT.md) before answering architecture questions. After modifying code in a session, run `graphify update .` to keep it current (AST-only, no API cost).

## Behavioral rules

See [CLAUDE.md](CLAUDE.md).
