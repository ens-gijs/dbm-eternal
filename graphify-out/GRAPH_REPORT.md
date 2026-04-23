# Graph Report - .  (2026-04-23)

## Corpus Check
- Corpus is ~45,586 words - fits in a single context window. You may not need a graph.

## Summary
- 852 nodes · 2034 edges · 42 communities detected
- Extraction: 51% EXTRACTED · 49% INFERRED · 0% AMBIGUOUS · INFERRED: 996 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Repository Registry & Platform Wiring|Repository Registry & Platform Wiring]]
- [[_COMMUNITY_Abstract Repository & Core Exceptions|Abstract Repository & Core Exceptions]]
- [[_COMMUNITY_Utility Tests & Statement Splitting|Utility Tests & Statement Splitting]]
- [[_COMMUNITY_Async Test Infrastructure|Async Test Infrastructure]]
- [[_COMMUNITY_AsyncVerifier Utilities|AsyncVerifier Utilities]]
- [[_COMMUNITY_Batch Execution Exceptions|Batch Execution Exceptions]]
- [[_COMMUNITY_Schema Migrator & Test Fixtures|Schema Migrator & Test Fixtures]]
- [[_COMMUNITY_Migration Loading Pipeline|Migration Loading Pipeline]]
- [[_COMMUNITY_Repository API & Contract Tests|Repository API & Contract Tests]]
- [[_COMMUNITY_Migration Data Models|Migration Data Models]]
- [[_COMMUNITY_Upsert SQL & Documentation|Upsert SQL & Documentation]]
- [[_COMMUNITY_Plugin & Resource Scanning|Plugin & Resource Scanning]]
- [[_COMMUNITY_Functional Utilities & Resource IO|Functional Utilities & Resource IO]]
- [[_COMMUNITY_Consumable Event System|Consumable Event System]]
- [[_COMMUNITY_Fake Repository Impl|Fake Repository Impl]]
- [[_COMMUNITY_MySQL Connection Config|MySQL Connection Config]]
- [[_COMMUNITY_Object Helper Utilities|Object Helper Utilities]]
- [[_COMMUNITY_Registry Test Fixtures|Registry Test Fixtures]]
- [[_COMMUNITY_Exception Unwrapping|Exception Unwrapping]]
- [[_COMMUNITY_ThrowingBiConsumer|ThrowingBiConsumer]]
- [[_COMMUNITY_ThrowingConsumer|ThrowingConsumer]]
- [[_COMMUNITY_Migration Parse Exception|Migration Parse Exception]]
- [[_COMMUNITY_Ambiguous API Exception|Ambiguous API Exception]]
- [[_COMMUNITY_Repo Init Exception|Repo Init Exception]]
- [[_COMMUNITY_Repo Not Registered Exception|Repo Not Registered Exception]]
- [[_COMMUNITY_SQL Dialect Enum|SQL Dialect Enum]]
- [[_COMMUNITY_ExceptionalFunction|ExceptionalFunction]]
- [[_COMMUNITY_ExceptionalSupplier|ExceptionalSupplier]]
- [[_COMMUNITY_ThrowingBiFunction|ThrowingBiFunction]]
- [[_COMMUNITY_SQLite & Upsert Generation|SQLite & Upsert Generation]]
- [[_COMMUNITY_Subscribable Event Hierarchy|Subscribable Event Hierarchy]]
- [[_COMMUNITY_Exceptional Suppliers|Exceptional Suppliers]]
- [[_COMMUNITY_Migration Package Info|Migration Package Info]]
- [[_COMMUNITY_Repository Package Info|Repository Package Info]]
- [[_COMMUNITY_RepositoryApi Annotation|RepositoryApi Annotation]]
- [[_COMMUNITY_Paper Platform Test Base|Paper Platform Test Base]]
- [[_COMMUNITY_Platform Package Info|Platform Package Info]]
- [[_COMMUNITY_SQL Package Info|SQL Package Info]]
- [[_COMMUNITY_PaperTestBaseLite|PaperTestBaseLite]]
- [[_COMMUNITY_Platform Package Sentinel|Platform Package Sentinel]]
- [[_COMMUNITY_SQL Package Sentinel|SQL Package Sentinel]]
- [[_COMMUNITY_ObjectHelpers Singleton|ObjectHelpers Singleton]]

## God Nodes (most connected - your core abstractions)
1. `SqlClient` - 41 edges
2. `of()` - 40 edges
3. `RepositoryRegistryTest` - 33 edges
4. `OneShotConsumableSubscribableEventTest` - 33 edges
5. `executeBatch()` - 32 edges
6. `RepositoryRegistry` - 30 edges
7. `ExecutorLimiter` - 24 edges
8. `commit()` - 23 edges
9. `rollback()` - 22 edges
10. `executeChunkedBatch()` - 22 edges

## Surprising Connections (you probably didn't know these)
- `SqlConnectionConfigTest` --references--> `SqlClient Usage Pattern`  [INFERRED]
  dbm-sql\src\test\java\io\github\ensgijs\dbm\sql\SqlConnectionConfigTest.java → README.md
- `RepositoryContractTest` --semantically_similar_to--> `RepositoryRegistryTest`  [INFERRED] [semantically similar]
  dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryContractTest.java → dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryRegistryTest.java
- `ChunkedBatchExecutionException` --semantically_similar_to--> `DatabaseException`  [INFERRED] [semantically similar]
  dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\ChunkedBatchExecutionException.java → dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\DatabaseException.java
- `DatabaseExceptionTest` --conceptually_related_to--> `SqlClient Usage Pattern`  [INFERRED]
  dbm-sql\src\test\java\io\github\ensgijs\dbm\sql\DatabaseExceptionTest.java → README.md
- `SqlClientTest` --references--> `SqlClient Usage Pattern`  [INFERRED]
  dbm-sql\src\test\java\io\github\ensgijs\dbm\sql\SqlClientTest.java → README.md

## Hyperedges (group relationships)
- **Migration Discovery and Execution Pipeline** — migration_MigrationLoader, migration_SchemaMigrator, migration_Migration [EXTRACTED 1.00]
- **Repository Bootstrap Lifecycle** — repository_RepositoryRegistry, repository_RepositoryRegistry_RegistrationHelper, repository_RepositoryRegistry_RegistrationBootstrappingContext [EXTRACTED 1.00]
- **Repository Conflict Resolution System** — repository_RepositoryRegistry_ConflictMode, repository_RepositoryRegistry_RegistrationOptions, repository_RepositoryRegistry [EXTRACTED 0.95]
- **Migration Topological Sort with Dependency Validation** — migrationsortest_migrationsortest, migration_migration, databaseexception_databaseexception [EXTRACTED 1.00]
- **Repository Registry Publish-Bind-Close Lifecycle** — repositoryregistrytest_repositoryregistrytest, repositoryregistry_repositoryregistry, conflictmode_conflictmode, registrationoptions_registrationoptions [EXTRACTED 0.95]
- **SqlClient Session/Transaction/Batch Execution Pipeline** — sqlclient_sqlclient, executioncontext_executioncontext, chunkedbatchexecutionexception_chunkedbatchexecutionexception [EXTRACTED 1.00]
- **Throwing Functional Interfaces Family** — throwingfunction_ThrowingFunction, throwingconsumer_ThrowingConsumer, throwingsupplier_ThrowingSupplier, throwingbiconsumer_ThrowingBiConsumer, throwingbifunction_ThrowingBiFunction [INFERRED 0.95]
- **Subscribable Event Hierarchy** — subscribableevent_SubscribableEvent, consumablesubscribableevent_ConsumableSubscribableEvent, oneshotconsumablesubscribableevent_OneShotConsumableSubscribableEvent [EXTRACTED 1.00]
- **SQL Dialect-Driven Components** — sqldialect_SqlDialect, sqliteconnectionconfig_SqliteConnectionConfig, upsertstatement_UpsertStatement [EXTRACTED 1.00]
- **Threading Test Infrastructure (AsyncVerifier, BlockingTestRunnable, TestRunnable)** — asyncverifier_asyncverifier, blockingtestrunnable_blockingtestrunnable, testrunnable_testrunnable [EXTRACTED 0.95]
- **Concurrency Limiting (ExecutorLimiter, LimitedVirtualThreadPerTaskExecutor, LimiterRunnableWrapper)** — executorlimiter_executorlimiter, limitedvirtualthreadpertaskexecutor_limitedvirtualthreadpertaskexecutor, executorlimiter_limiterrunnablewrapper [EXTRACTED 1.00]
- **Functional Exception Handling (ValueOrException, RetrievalException, ExceptionalSupplier)** — valueorexception_valueorexception, valueorexception_retrievalexception, valueorexceptiontest_valueorexceptiontest [INFERRED 0.85]

## Communities

### Community 0 - "Repository Registry & Platform Wiring"
Cohesion: 0.03
Nodes (38): ConflictMode, PlatformHandle, RegistrationOptions, RepositoryComposition, RegistrationBootstrappingContext, RegistrationHelper, RegistrationOptions, RepositoryRegistry (+30 more)

### Community 1 - "Abstract Repository & Core Exceptions"
Cohesion: 0.04
Nodes (22): AbstractRepository, DatabaseException, ExecutionContext, FakeRepository, FakeRepositoryImpl, Migration(), MigrationSortTest, MySqlConnectionConfig (+14 more)

### Community 2 - "Utility Tests & Statement Splitting"
Cohesion: 0.06
Nodes (10): ConsumableSubscribableEventTest, sort(), MigrationSortTest, OneShotConsumableSubscribableEvent, OneShotConsumableSubscribableEventTest, SqlStatementSplitter, StatementSplitException, SqlStatementSplitterTest (+2 more)

### Community 3 - "Async Test Infrastructure"
Cohesion: 0.05
Nodes (12): DatabaseExceptionTest, MySqlTests, SqlConnectionConfigTest, SqliteTests, configurePool(), getDbUrl(), isEquivalent(), maxConnections() (+4 more)

### Community 4 - "AsyncVerifier Utilities"
Cohesion: 0.05
Nodes (9): AsyncVerifier, TestFailure, BlockingTestRunnable, ExecutorLimiter, LimiterRunnableWrapper, ExecutorLimiterTest, LimitedVirtualThreadPerTaskExecutor, OneShotCondition (+1 more)

### Community 5 - "Batch Execution Exceptions"
Cohesion: 0.1
Nodes (16): ChunkedBatchExecutionException, commit(), executeBatch(), executeChunkedBatch(), executeQuery(), executeUpdate(), prepare(), rollback() (+8 more)

### Community 6 - "Schema Migrator & Test Fixtures"
Cohesion: 0.06
Nodes (15): FakeProgrammaticMigration, AnnotatedConcreteClass, MigrationProvider, SchemaMigrator, BaseRepo, EmptyAnnotRepo, MigrateTests, MissingAnnotRepo (+7 more)

### Community 7 - "Migration Loading Pipeline"
Cohesion: 0.07
Nodes (12): MigrationSource, ProgrammaticMigration, sourceType(), toString(), version(), MigrationLoader, of(), LoadedResourcesTests (+4 more)

### Community 8 - "Repository API & Contract Tests"
Cohesion: 0.06
Nodes (17): Repository, ChildApi, ChildImpl, CollectMigrationNamesTest, DualApiImpl, IdentifyRepositoryApiTest, IndirectApi, ItemRepo (+9 more)

### Community 9 - "Migration Data Models"
Cohesion: 0.14
Nodes (31): Migration, MigrationLoader, MigrationLoader.MigrationFileParseResult, MigrationLoader.ParsedMigrationFileName, MigrationParseException, Migration.JavaSource, Migration.Key, Migration.MigrationSource (+23 more)

### Community 10 - "Upsert SQL & Documentation"
Cohesion: 0.13
Nodes (11): dbm-core Module, dbm-eternal Project README, dbm-sql Module, Migration File Naming Convention, Multi-Platform Design Intent, Repository Pattern, SqlClient Usage Pattern, UpsertStatement Usage (+3 more)

### Community 11 - "Plugin & Resource Scanning"
Cohesion: 0.24
Nodes (4): RepositoryRegistryScanTest, asReader(), asStream(), ResourceScanner

### Community 12 - "Functional Utilities & Resource IO"
Cohesion: 0.21
Nodes (12): BubbleUpException, ExceptionalFunction Interface, ResourceEntry Record, ResourceScanner Utility, SqlStatementSplitter Utility, StatementSplitException, ChunkedBatchExecutionException, StatementExecutor Record (+4 more)

### Community 13 - "Consumable Event System"
Cohesion: 0.31
Nodes (1): ConsumableSubscribableEvent

### Community 14 - "Fake Repository Impl"
Cohesion: 0.29
Nodes (1): FakeRepositoryImpl

### Community 15 - "MySQL Connection Config"
Cohesion: 0.33
Nodes (2): configurePool(), getDbUrl()

### Community 16 - "Object Helper Utilities"
Cohesion: 0.33
Nodes (1): ObjectHelpers

### Community 17 - "Registry Test Fixtures"
Cohesion: 0.5
Nodes (1): FakeRepoImpl

### Community 18 - "Exception Unwrapping"
Cohesion: 0.5
Nodes (1): BubbleUpException

### Community 19 - "ThrowingBiConsumer"
Cohesion: 0.67
Nodes (1): ThrowingBiConsumer

### Community 20 - "ThrowingConsumer"
Cohesion: 0.67
Nodes (1): ThrowingConsumer

### Community 21 - "Migration Parse Exception"
Cohesion: 0.67
Nodes (1): MigrationParseException

### Community 22 - "Ambiguous API Exception"
Cohesion: 0.67
Nodes (1): AmbiguousRepositoryApiException

### Community 23 - "Repo Init Exception"
Cohesion: 0.67
Nodes (1): RepositoryInitializationException

### Community 24 - "Repo Not Registered Exception"
Cohesion: 0.67
Nodes (1): RepositoryNotRegisteredException

### Community 25 - "SQL Dialect Enum"
Cohesion: 0.67
Nodes (0): 

### Community 26 - "ExceptionalFunction"
Cohesion: 0.67
Nodes (1): ExceptionalFunction

### Community 27 - "ExceptionalSupplier"
Cohesion: 0.67
Nodes (1): ExceptionalSupplier

### Community 28 - "ThrowingBiFunction"
Cohesion: 0.67
Nodes (1): ThrowingBiFunction

### Community 29 - "SQLite & Upsert Generation"
Cohesion: 0.67
Nodes (3): SqlDialect Enum, SqliteConnectionConfig Record, UpsertStatement Builder

### Community 30 - "Subscribable Event Hierarchy"
Cohesion: 1.0
Nodes (3): ConsumableSubscribableEvent, OneShotConsumableSubscribableEvent, SubscribableEvent Interface

### Community 31 - "Exceptional Suppliers"
Cohesion: 1.0
Nodes (2): ExceptionalSupplier Interface, ThrowingSupplier Interface

### Community 32 - "Migration Package Info"
Cohesion: 1.0
Nodes (0): 

### Community 33 - "Repository Package Info"
Cohesion: 1.0
Nodes (0): 

### Community 34 - "RepositoryApi Annotation"
Cohesion: 1.0
Nodes (0): 

### Community 35 - "Paper Platform Test Base"
Cohesion: 1.0
Nodes (0): 

### Community 36 - "Platform Package Info"
Cohesion: 1.0
Nodes (0): 

### Community 37 - "SQL Package Info"
Cohesion: 1.0
Nodes (0): 

### Community 38 - "PaperTestBaseLite"
Cohesion: 1.0
Nodes (1): PaperTestBaseLite (commented out)

### Community 39 - "Platform Package Sentinel"
Cohesion: 1.0
Nodes (1): Platform Package (dbm-sql)

### Community 40 - "SQL Package Sentinel"
Cohesion: 1.0
Nodes (1): SQL Package (dbm-sql)

### Community 41 - "ObjectHelpers Singleton"
Cohesion: 1.0
Nodes (1): ObjectHelpers Utility

## Knowledge Gaps
- **38 isolated node(s):** `BaseRepo`, `MissingAnnotRepo`, `EmptyAnnotRepo`, `ItemRepo`, `OrderRepo` (+33 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Exceptional Suppliers`** (2 nodes): `ExceptionalSupplier Interface`, `ThrowingSupplier Interface`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Migration Package Info`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Repository Package Info`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `RepositoryApi Annotation`** (1 nodes): `RepositoryApi.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Paper Platform Test Base`** (1 nodes): `PaperTestBaseLite.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Platform Package Info`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `SQL Package Info`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `PaperTestBaseLite`** (1 nodes): `PaperTestBaseLite (commented out)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Platform Package Sentinel`** (1 nodes): `Platform Package (dbm-sql)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `SQL Package Sentinel`** (1 nodes): `SQL Package (dbm-sql)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `ObjectHelpers Singleton`** (1 nodes): `ObjectHelpers Utility`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `RepositoryRegistryTest` connect `Repository Registry & Platform Wiring` to `Abstract Repository & Core Exceptions`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `SqlClient` connect `Abstract Repository & Core Exceptions` to `Repository Registry & Platform Wiring`, `Batch Execution Exceptions`, `Schema Migrator & Test Fixtures`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Why does `of()` connect `Migration Loading Pipeline` to `Repository Registry & Platform Wiring`, `Abstract Repository & Core Exceptions`, `Utility Tests & Statement Splitting`, `Async Test Infrastructure`, `Batch Execution Exceptions`, `Schema Migrator & Test Fixtures`, `Repository API & Contract Tests`, `Plugin & Resource Scanning`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `SqlClient` (e.g. with `SqlDatabaseManager` and `MySqlConnectionConfig`) actually correct?**
  _`SqlClient` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 39 inferred relationships involving `of()` (e.g. with `.find()` and `.testFileNameParsingSql_HappyCase_MySql_ForwardSlashes()`) actually correct?**
  _`of()` has 39 INFERRED edges - model-reasoned connections that need verification._
- **Are the 29 inferred relationships involving `executeBatch()` (e.g. with `.getConnection()` and `commit()`) actually correct?**
  _`executeBatch()` has 29 INFERRED edges - model-reasoned connections that need verification._
- **What connects `BaseRepo`, `MissingAnnotRepo`, `EmptyAnnotRepo` to the rest of the system?**
  _38 weakly-connected nodes found - possible documentation gaps or missing edges._