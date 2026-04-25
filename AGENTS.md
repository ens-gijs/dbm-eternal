# AGENTS.md — dbm-eternal

Platform-agnostic SQL database management library. Multi-module Gradle project, Java 21, Apache 2.0.

## Modules

| Module | Purpose | Scoped doc                              |
|---|---|-----------------------------------------|
| `dbm-sql` | Core SQL layer: `SqlClient`, connection configs (MySQL/SQLite), batch/upsert helpers, dialects, utilities | [dbm-sql/AGENTS.md](dbm-sql/AGENT.md)   |
| `dbm-core` | Schema migrations + repository registry, built on `dbm-sql` | [dbm-core/AGENTS.md](dbm-core/AGENT.md) |
| `dbm-platform-paper` | Paper (Minecraft) integration — **in progress, do not modify without instruction** | —                                       |
| `dbm-platform-velocity` | Velocity (Minecraft) integration — **in progress, do not modify without instruction** | —                                       |

`dbm-core` depends on `dbm-sql`. Platform modules are out of scope for current work.

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
- Group/version: `io.github.ensgijs.dbm` / `1.0.0-SNAPSHOT` (managed in root `build.gradle`).

## Repository conventions

- Package root: `io.github.ensgijs.dbm` across all modules.
- Test framework: JUnit 5 (Jupiter) + Mockito; do not introduce JUnit 4 or other frameworks.
- Public API uses `org.jetbrains.annotations` (`@Nullable`, `@NotNull`) at `compileOnly` scope.
- Library code — no main classes, no `System.out` logging in production paths. Prefer throwing typed exceptions over silent failures.

## Knowledge graph

A graphify knowledge graph exists at [graphify-out/](graphify-out/). Read [graphify-out/GRAPH_REPORT.md](graphify-out/GRAPH_REPORT.md) before answering architecture questions. After modifying code in a session, run `graphify update .` to keep it current (AST-only, no API cost).

## Behavioral rules

See [CLAUDE.md](CLAUDE.md) for the full set. Highlights:

- **Surgical changes.** Touch only what the task requires; match existing style; do not refactor adjacent code.
- **Simplicity.** No speculative abstractions, configurability, or error handling for impossible scenarios.
- **Ask before assuming.** State assumptions explicitly; surface ambiguity instead of picking silently.
- **Verify, don't claim.** Define a success check before coding; loop until it passes.
