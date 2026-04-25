# Graph Report - .  (2026-04-24)

## Corpus Check
- 18 files · ~0 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 925 nodes · 2274 edges · 44 communities detected
- Extraction: 51% EXTRACTED · 49% INFERRED · 0% AMBIGUOUS · INFERRED: 1124 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_SQL Client & Migration Core|SQL Client & Migration Core]]
- [[_COMMUNITY_Repository Registry & Providers|Repository Registry & Providers]]
- [[_COMMUNITY_Event System & SQL Utilities|Event System & SQL Utilities]]
- [[_COMMUNITY_Schema Migration Pipeline|Schema Migration Pipeline]]
- [[_COMMUNITY_Repository Base & Exceptions|Repository Base & Exceptions]]
- [[_COMMUNITY_SQLite & Connection Config|SQLite & Connection Config]]
- [[_COMMUNITY_Async Test Utilities|Async Test Utilities]]
- [[_COMMUNITY_Batch Execution & Chunking|Batch Execution & Chunking]]
- [[_COMMUNITY_Registry Test Fixtures|Registry Test Fixtures]]
- [[_COMMUNITY_Database Exception Tests|Database Exception Tests]]
- [[_COMMUNITY_Migration File Parsing|Migration File Parsing]]
- [[_COMMUNITY_Manager & Migrator Integration|Manager & Migrator Integration]]
- [[_COMMUNITY_Dialect Annotation & SQLite Memory|Dialect Annotation & SQLite Memory]]
- [[_COMMUNITY_Plugin Scanning|Plugin Scanning]]
- [[_COMMUNITY_Utility Exceptions|Utility Exceptions]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]

## God Nodes (most connected - your core abstractions)
1. `SqlClient` - 43 edges
2. `of()` - 40 edges
3. `RepositoryRegistryTest` - 33 edges
4. `OneShotConsumableSubscribableEventTest` - 33 edges
5. `RepositoryRegistry` - 32 edges
6. `executeBatch()` - 32 edges
7. `ExecutorLimiter` - 24 edges
8. `commit()` - 23 edges
9. `rollback()` - 22 edges
10. `executeChunkedBatch()` - 22 edges

## Surprising Connections (you probably didn't know these)
- `SqlConnectionConfigTest` --references--> `SqlClient Usage Pattern`  [INFERRED]
  dbm-sql\src\test\java\io\github\ensgijs\dbm\sql\SqlConnectionConfigTest.java → README.md
- `SqlClient` --semantically_similar_to--> `SqlDatabaseManager`  [INFERRED] [semantically similar]
  dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\SqlClient.java → dbm-core\src\main\java\io\github\ensgijs\dbm\sql\SqlDatabaseManager.java
- `RepositoryContractTest` --semantically_similar_to--> `RepositoryRegistryTest`  [INFERRED] [semantically similar]
  dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryContractTest.java → dbm-core\src\test\java\io\github\ensgijs\dbm\repository\RepositoryRegistryTest.java
- `ChunkedBatchExecutionException` --semantically_similar_to--> `DatabaseException`  [INFERRED] [semantically similar]
  dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\ChunkedBatchExecutionException.java → dbm-sql\src\main\java\io\github\ensgijs\dbm\sql\DatabaseException.java
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

### Community 0 - "SQL Client & Migration Core"
Cohesion: 0.04
Nodes (24): FakeProgrammaticMigration, MigrationProvider, SchemaMigrator, MigrateTests, SqlClient, InitializationTests, SetSqlConnectionConfigTests, ShutdownTests (+16 more)

### Community 1 - "Repository Registry & Providers"
Cohesion: 0.05
Nodes (17): ConflictMode, PlatformHandle, RegistrationOptions, RepositoryComposition, RegistrationBootstrappingContext, RegistrationHelper, RegistrationOptions, RepositoryRegistry (+9 more)

### Community 2 - "Event System & SQL Utilities"
Cohesion: 0.07
Nodes (8): ConsumableSubscribableEventTest, OneShotConsumableSubscribableEvent, OneShotConsumableSubscribableEventTest, SqlStatementSplitter, StatementSplitException, SqlStatementSplitterTest, SubscribableEvent, ThrowingSupplier

### Community 3 - "Schema Migration Pipeline"
Cohesion: 0.06
Nodes (16): MigrationSource, ProgrammaticMigration, sort(), sourceType(), toString(), version(), MigrationLoader, of() (+8 more)

### Community 4 - "Repository Base & Exceptions"
Cohesion: 0.04
Nodes (28): AbstractRepository, DatabaseException, ExecutionContext, FakeRepository, FakeRepositoryImpl, Migration(), MigrationSortTest, Repository (+20 more)

### Community 5 - "SQLite & Connection Config"
Cohesion: 0.07
Nodes (10): MySqlTests, SqlConnectionConfigTest, SqliteTests, configurePool(), getDbUrl(), isEquivalent(), maxConnections(), RetrievalException (+2 more)

### Community 6 - "Async Test Utilities"
Cohesion: 0.05
Nodes (9): AsyncVerifier, TestFailure, BlockingTestRunnable, ExecutorLimiter, LimiterRunnableWrapper, ExecutorLimiterTest, LimitedVirtualThreadPerTaskExecutor, OneShotCondition (+1 more)

### Community 7 - "Batch Execution & Chunking"
Cohesion: 0.1
Nodes (16): ChunkedBatchExecutionException, commit(), executeBatch(), executeChunkedBatch(), executeQuery(), executeUpdate(), prepare(), rollback() (+8 more)

### Community 8 - "Registry Test Fixtures"
Cohesion: 0.05
Nodes (25): AbstractBaseLogic, AbstractMidLevel, AltFakeRepoImpl, BaseLogic, BaseLogicImpl, CircularDependencyTests, DeepChainImpl, DirectLogic (+17 more)

### Community 9 - "Database Exception Tests"
Cohesion: 0.08
Nodes (12): DatabaseExceptionTest, dbm-core Module, dbm-eternal Project README, dbm-sql Module, Migration File Naming Convention, Multi-Platform Design Intent, Repository Pattern, SqlClient Usage Pattern (+4 more)

### Community 10 - "Migration File Parsing"
Cohesion: 0.14
Nodes (31): Migration, MigrationLoader, MigrationLoader.MigrationFileParseResult, MigrationLoader.ParsedMigrationFileName, MigrationParseException, Migration.JavaSource, Migration.Key, Migration.MigrationSource (+23 more)

### Community 11 - "Manager & Migrator Integration"
Cohesion: 0.09
Nodes (8): MySqlConnectionConfig, BaseRepo, EmptyAnnotRepo, MissingAnnotRepo, SchemaMigratorTest, SqlConnectionConfig, SqlDatabaseManager, SqlDatabaseManagerTest

### Community 12 - "Dialect Annotation & SQLite Memory"
Cohesion: 0.11
Nodes (11): DuplicateDialect, MultiDialect, NoAnnotation, RepositoryImplTest, SqliteOnly, WithUndefined, configurePool(), getDbUrl() (+3 more)

### Community 13 - "Plugin Scanning"
Cohesion: 0.23
Nodes (4): RepositoryRegistryScanTest, asReader(), asStream(), ResourceScanner

### Community 14 - "Utility Exceptions"
Cohesion: 0.21
Nodes (12): BubbleUpException, ExceptionalFunction Interface, ResourceEntry Record, ResourceScanner Utility, SqlStatementSplitter Utility, StatementSplitException, ChunkedBatchExecutionException, StatementExecutor Record (+4 more)

### Community 15 - "Community 15"
Cohesion: 0.31
Nodes (1): ConsumableSubscribableEvent

### Community 16 - "Community 16"
Cohesion: 0.29
Nodes (1): FakeRepositoryImpl

### Community 17 - "Community 17"
Cohesion: 0.33
Nodes (2): configurePool(), getDbUrl()

### Community 18 - "Community 18"
Cohesion: 0.33
Nodes (1): ObjectHelpers

### Community 19 - "Community 19"
Cohesion: 0.5
Nodes (1): BubbleUpException

### Community 20 - "Community 20"
Cohesion: 0.67
Nodes (1): ThrowingBiConsumer

### Community 21 - "Community 21"
Cohesion: 0.67
Nodes (1): ThrowingConsumer

### Community 22 - "Community 22"
Cohesion: 0.67
Nodes (1): MigrationParseException

### Community 23 - "Community 23"
Cohesion: 0.67
Nodes (1): AmbiguousRepositoryApiException

### Community 24 - "Community 24"
Cohesion: 0.67
Nodes (1): RepositoryInitializationException

### Community 25 - "Community 25"
Cohesion: 0.67
Nodes (1): RepositoryNotRegisteredException

### Community 26 - "Community 26"
Cohesion: 0.67
Nodes (0): 

### Community 27 - "Community 27"
Cohesion: 0.67
Nodes (1): ExceptionalFunction

### Community 28 - "Community 28"
Cohesion: 0.67
Nodes (1): ExceptionalSupplier

### Community 29 - "Community 29"
Cohesion: 0.67
Nodes (1): ThrowingBiFunction

### Community 30 - "Community 30"
Cohesion: 0.67
Nodes (3): SqlDialect Enum, SqliteConnectionConfig Record, UpsertStatement Builder

### Community 31 - "Community 31"
Cohesion: 1.0
Nodes (3): ConsumableSubscribableEvent, OneShotConsumableSubscribableEvent, SubscribableEvent Interface

### Community 32 - "Community 32"
Cohesion: 1.0
Nodes (2): ExceptionalSupplier Interface, ThrowingSupplier Interface

### Community 33 - "Community 33"
Cohesion: 1.0
Nodes (0): 

### Community 34 - "Community 34"
Cohesion: 1.0
Nodes (0): 

### Community 35 - "Community 35"
Cohesion: 1.0
Nodes (0): 

### Community 36 - "Community 36"
Cohesion: 1.0
Nodes (0): 

### Community 37 - "Community 37"
Cohesion: 1.0
Nodes (0): 

### Community 38 - "Community 38"
Cohesion: 1.0
Nodes (0): 

### Community 39 - "Community 39"
Cohesion: 1.0
Nodes (1): PaperTestBaseLite (commented out)

### Community 40 - "Community 40"
Cohesion: 1.0
Nodes (1): Platform Package (dbm-sql)

### Community 41 - "Community 41"
Cohesion: 1.0
Nodes (1): SQL Package (dbm-sql)

### Community 42 - "Community 42"
Cohesion: 1.0
Nodes (1): ObjectHelpers Utility

### Community 43 - "Community 43"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **43 isolated node(s):** `BaseRepo`, `MissingAnnotRepo`, `EmptyAnnotRepo`, `ItemRepo`, `OrderRepo` (+38 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 32`** (2 nodes): `ExceptionalSupplier Interface`, `ThrowingSupplier Interface`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 33`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 34`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (1 nodes): `RepositoryApi.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 36`** (1 nodes): `PaperTestBaseLite.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 37`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (1 nodes): `PaperTestBaseLite (commented out)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 40`** (1 nodes): `Platform Package (dbm-sql)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 41`** (1 nodes): `SQL Package (dbm-sql)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 42`** (1 nodes): `ObjectHelpers Utility`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 43`** (1 nodes): `RepositoryImpl.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `RepositoryRegistryTest` connect `Repository Registry & Providers` to `Registry Test Fixtures`, `Manager & Migrator Integration`, `Repository Base & Exceptions`?**
  _High betweenness centrality (0.084) - this node is a cross-community bridge._
- **Why does `SqlClient` connect `SQL Client & Migration Core` to `Repository Registry & Providers`, `Schema Migration Pipeline`, `Repository Base & Exceptions`, `Batch Execution & Chunking`, `Manager & Migrator Integration`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Why does `of()` connect `Schema Migration Pipeline` to `SQL Client & Migration Core`, `Repository Registry & Providers`, `Event System & SQL Utilities`, `Repository Base & Exceptions`, `Batch Execution & Chunking`, `Plugin Scanning`?**
  _High betweenness centrality (0.042) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `SqlClient` (e.g. with `SqlDatabaseManager` and `MySqlConnectionConfig`) actually correct?**
  _`SqlClient` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 39 inferred relationships involving `of()` (e.g. with `.find()` and `.testFileNameParsingSql_HappyCase_MySql_ForwardSlashes()`) actually correct?**
  _`of()` has 39 INFERRED edges - model-reasoned connections that need verification._
- **What connects `BaseRepo`, `MissingAnnotRepo`, `EmptyAnnotRepo` to the rest of the system?**
  _43 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SQL Client & Migration Core` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._